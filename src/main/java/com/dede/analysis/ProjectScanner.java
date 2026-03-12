package com.dede.analysis;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

@Component
public class ProjectScanner {

    private final SourceParser sourceParser;
    private final OsgiManifestParser manifestParser;
    private final JarScanner jarScanner;
    private final SlingResourceParser slingResourceParser;
    private final SlingHtlParser slingHtlParser;

    public ProjectScanner(SourceParser sourceParser, OsgiManifestParser manifestParser, JarScanner jarScanner, 
                          SlingResourceParser slingResourceParser, SlingHtlParser slingHtlParser) {
        this.sourceParser = sourceParser;
        this.manifestParser = manifestParser;
        this.jarScanner = jarScanner;
        this.slingResourceParser = slingResourceParser;
        this.slingHtlParser = slingHtlParser;
    }

    public void scan(String rootPath) {
        Path root = Paths.get(rootPath);
        if (!Files.exists(root)) {
            System.err.println("Path does not exist: " + rootPath);
            return;
        }

        System.out.println("Scanning: " + root.toAbsolutePath());
        
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                 .forEach(p -> {
                     String fileName = p.toString();
                     if (fileName.endsWith(".java")) {
                         sourceParser.parse(p);
                     } else if (fileName.endsWith("MANIFEST.MF")) {
                         manifestParser.parse(p);
                     } else if (fileName.endsWith(".jar")) {
                         jarScanner.scan(p);
                     } else if (fileName.endsWith(".content.xml")) {
                         slingResourceParser.parse(p);
                     } else if (fileName.endsWith(".html")) {
                         slingHtlParser.parse(p);
                     }
                 });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
