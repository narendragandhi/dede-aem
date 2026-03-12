package com.dede.core.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Adapter (Implementation) using Jackson for JSON persistence.
 */
@Component
public class JsonMetadataCache implements MetadataCache {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final File cacheFile = new File(".dede-cache.json");
    private Map<String, BundleMetadata> cache = new HashMap<>();

    @PostConstruct
    @Override
    public void load() {
        if (cacheFile.exists()) {
            try {
                cache = objectMapper.readValue(cacheFile, new TypeReference<Map<String, BundleMetadata>>() {});
            } catch (IOException e) {
                System.err.println("Could not load cache: " + e.getMessage());
            }
        }
    }

    @Override
    public Optional<BundleMetadata> get(Path jarPath) {
        BundleMetadata metadata = cache.get(jarPath.toAbsolutePath().toString());
        if (metadata != null) {
            File file = jarPath.toFile();
            // Invalidation logic: Check if file changed since last scan
            if (file.lastModified() == metadata.lastModified() && file.length() == metadata.fileSize()) {
                return Optional.of(metadata);
            }
        }
        return Optional.empty();
    }

    @Override
    public void put(Path jarPath, BundleMetadata metadata) {
        cache.put(jarPath.toAbsolutePath().toString(), metadata);
    }

    @Override
    public void save() {
        try {
            System.out.println("Saving cache to: " + cacheFile.getAbsolutePath());
            objectMapper.writeValue(cacheFile, cache);
            System.out.println("Cache saved successfully. Size: " + cache.size());
        } catch (IOException e) {
            System.err.println("Could not save cache: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
