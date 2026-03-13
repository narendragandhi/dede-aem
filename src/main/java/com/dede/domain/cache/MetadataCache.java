package com.dede.domain.cache;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Port (Interface) for metadata persistence.
 */
public interface MetadataCache {
    Optional<BundleMetadata> get(Path jarPath);
    void put(Path jarPath, BundleMetadata metadata);
    void save();
    void load();
}
