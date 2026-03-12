package com.dede.analysis;

import com.dede.core.GraphService;
import com.dede.core.cache.BundleMetadata;
import com.dede.core.cache.MetadataCache;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Optional;

@Service
public class DedeOsgiScanner {

    private final List<ProjectParser> parsers;
    private final OsgiLinker linker;
    private final MetadataCache metadataCache;

    public DedeOsgiScanner(List<ProjectParser> parsers, OsgiLinker linker, MetadataCache metadataCache) {
        this.parsers = parsers;
        this.linker = linker;
        this.metadataCache = metadataCache;
    }

    public void scan(String projectRoot) throws IOException {
        Path root = Paths.get(projectRoot);
        System.out.println("Scanning project: " + root.toAbsolutePath());

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                // Execute all supporting parsers for this file
                parsers.stream()
                        .filter(p -> p.supports(file))
                        .forEach(p -> p.parse(file));
                
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.getFileName().toString().equals("target") || 
                    dir.getFileName().toString().equals("node_modules") ||
                    dir.getFileName().toString().startsWith(".")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
        });

        // Post-scan linking
        linker.link();
    }
}
