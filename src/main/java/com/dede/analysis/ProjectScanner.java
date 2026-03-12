package com.dede.analysis;

import com.dede.core.GraphService;
import com.dede.core.model.CodeNode;
import com.dede.core.model.NodeType;
import com.dede.core.model.RelationshipType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

@Component
public class ProjectScanner {

    private final SourceParser sourceParser;
    private final OsgiManifestParser manifestParser;
    private final SlingHtlParser htlParser;
    private final JcrContentParser jcrParser;
    private final GraphService graphService;

    public ProjectScanner(SourceParser sourceParser, OsgiManifestParser manifestParser, 
                          SlingHtlParser htlParser, JcrContentParser jcrParser, GraphService graphService) {
        this.sourceParser = sourceParser;
        this.manifestParser = manifestParser;
        this.htlParser = htlParser;
        this.jcrParser = jcrParser;
        this.graphService = graphService;
    }

    public void scan(String rootPath) throws IOException {
        Path root = Paths.get(rootPath);
        
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String fileName = file.getFileName().toString();
                
                if (fileName.endsWith(".java")) {
                    sourceParser.parse(file);
                } else if (fileName.equals("MANIFEST.MF")) {
                    manifestParser.parse(file);
                } else if (fileName.endsWith(".html")) {
                    htlParser.parse(file);
                } else if (fileName.equals(".content.xml")) {
                    jcrParser.parse(file);
                }
                
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                // Detect AEM Component folders (ui.apps)
                if (Files.exists(dir.resolve(".content.xml"))) {
                    detectAemComponent(dir);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void detectAemComponent(Path dir) {
        // Simple heuristic: if inside /apps/ and has .content.xml
        String path = dir.toString();
        if (path.contains("/apps/")) {
            String resType = path.substring(path.indexOf("/apps/") + 6);
            CodeNode compNode = new CodeNode("res:" + resType, resType, NodeType.JCR_RESOURCE_TYPE, resType, path);
            graphService.addNode(compNode);
        }
    }
}
