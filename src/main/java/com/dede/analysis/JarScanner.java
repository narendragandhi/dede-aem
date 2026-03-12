package com.dede.analysis;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

@Component
public class JarScanner {

    private final OsgiManifestParser manifestParser;
    private final OsgiServiceParser serviceParser;
    private final com.dede.core.cache.MetadataCache cache;

    public JarScanner(OsgiManifestParser manifestParser, OsgiServiceParser serviceParser, com.dede.core.cache.MetadataCache cache) {
        this.manifestParser = manifestParser;
        this.serviceParser = serviceParser;
        this.cache = cache;
    }

    public void scan(Path jarPath) {
        var cached = cache.get(jarPath);
        if (cached.isPresent()) {
            manifestParser.buildGraphFromMetadata(cached.get(), jarPath.toString());
            return;
        }

        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            Manifest manifest = jarFile.getManifest();
            if (manifest == null) return;

            // Step 1: Parse Manifest
            var metadata = manifestParser.parseFromManifestObject(manifest, jarPath.toString());
            if (metadata == null) return;

            // Step 2: Parse OSGI-INF Services
            List<String> provided = new java.util.ArrayList<>();
            List<String> consumed = new java.util.ArrayList<>();

            jarFile.stream()
                .filter(entry -> entry.getName().startsWith("OSGI-INF/") && entry.getName().endsWith(".xml"))
                .forEach(entry -> {
                    try (InputStream is = jarFile.getInputStream(entry)) {
                        // In a real app we'd need to duplicate the stream or use a byte array
                        // For MVP, we just parse once.
                        provided.addAll(serviceParser.parseProvidedServices(jarFile.getInputStream(entry)));
                        consumed.addAll(serviceParser.parseConsumedServices(jarFile.getInputStream(entry)));
                    } catch (IOException e) {}
                });

            // Update metadata with services
            var fullMetadata = new com.dede.core.cache.BundleMetadata(
                metadata.symbolicName(), metadata.bundleVersion(), 
                metadata.exports(), metadata.imports(),
                provided, consumed, metadata.lastModified(), metadata.fileSize()
            );

            cache.put(jarPath, fullMetadata);
            // Re-build graph with full metadata
            manifestParser.buildGraphFromMetadata(fullMetadata, jarPath.toString());

        } catch (IOException e) {
        }
    }
}
