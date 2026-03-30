package com.dede.discovery;

import com.dede.domain.GraphService;
import com.dede.domain.model.CodeNode;
import com.dede.domain.model.NodeType;
import com.dede.domain.model.RelationshipType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

@Slf4j
@Component
public class ProjectScanner {

    private final SourceParser sourceParser;
    private final OsgiManifestParser manifestParser;
    private final SlingHtlParser htlParser;
    private final JcrContentParser jcrParser;
    private final DispatcherParser dispatcherParser;
    private final ApacheConfigParser apacheParser;
    private final GraphService graphService;

    public ProjectScanner(SourceParser sourceParser, OsgiManifestParser manifestParser, 
                          SlingHtlParser htlParser, JcrContentParser jcrParser, 
                          DispatcherParser dispatcherParser, ApacheConfigParser apacheParser, 
                          GraphService graphService) {
        this.sourceParser = sourceParser;
        this.manifestParser = manifestParser;
        this.htlParser = htlParser;
        this.jcrParser = jcrParser;
        this.dispatcherParser = dispatcherParser;
        this.apacheParser = apacheParser;
        this.graphService = graphService;
    }

    public void scan(String rootPath) throws IOException {
        Path root = Paths.get(rootPath);
        
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                processFile(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (Files.exists(dir.resolve(".content.xml"))) {
                    detectAemComponent(dir);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Watches a directory for changes and updates the graph incrementally.
     */
    public void startWatching(String rootPath) throws IOException {
        Path root = Paths.get(rootPath);
        WatchService watchService = FileSystems.getDefault().newWatchService();
        
        // Register all directories recursively
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                dir.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE);
                return FileVisitResult.CONTINUE;
            }
        });

        log.info("Started watching for changes in: {}", rootPath);

        while (true) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            for (WatchEvent<?> event : key.pollEvents()) {
                Path relativePath = (Path) event.context();
                Path fullPath = ((Path) key.watchable()).resolve(relativePath);

                if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
                    log.info("File deleted: {}, removing nodes from graph.", fullPath);
                    graphService.removeNodesByFilePath(fullPath.toString());
                } else if (Files.isRegularFile(fullPath)) {
                    log.info("File changed: {}, performing incremental re-scan.", fullPath);
                    graphService.removeNodesByFilePath(fullPath.toString());
                    processFile(fullPath);
                }
            }
            key.reset();
        }
    }

    private void processFile(Path file) {
        String fileName = file.getFileName().toString();
        
        if (fileName.endsWith(".java")) {
            sourceParser.parse(file);
        } else if (fileName.equals("MANIFEST.MF")) {
            manifestParser.parse(file);
        } else if (fileName.endsWith(".html")) {
            htlParser.parse(file);
        } else if (fileName.equals(".content.xml")) {
            jcrParser.parse(file);
        } else if (dispatcherParser.supports(file)) {
            dispatcherParser.parse(file);
        } else if (apacheParser.supports(file)) {
            apacheParser.parse(file);
        }
    }

    private void detectAemComponent(Path dir) {
        String path = dir.toString();
        if (path.contains("/apps/")) {
            String resType = path.substring(path.indexOf("/apps/") + 6);
            CodeNode compNode = new CodeNode("res:" + resType, resType, NodeType.JCR_RESOURCE_TYPE, resType, path);
            graphService.addNode(compNode);
        }
    }
}
