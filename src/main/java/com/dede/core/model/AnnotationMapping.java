package com.dede.core.model;

import lombok.Data;
import java.util.List;

@Data
public class AnnotationMapping {
    private List<Mapping> mappings;

    @Data
    public static class Mapping {
        private String annotationName; // e.g., "SlingServletPaths"
        private String nodeType;       // e.g., "OSGI_COMPONENT"
        private String attributeName;  // e.g., "paths"
        private String relationship;   // e.g., "PROVIDES"
        private String idPrefix;       // e.g., "path:"
    }
}
