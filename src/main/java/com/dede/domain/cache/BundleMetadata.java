package com.dede.domain.cache;

import java.util.List;
import java.util.Map;

/**
 * Modern Java Record for immutable metadata storage.
 */
public record BundleMetadata(
    String symbolicName,
    String bundleVersion,
    Map<String, String> exports, // Package -> Version
    Map<String, String> imports, // Package -> Version Range
    List<String> providedServices,
    List<String> consumedServices,
    long lastModified,
    long fileSize
) {}
