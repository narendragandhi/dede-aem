package com.dede.domain.model;

import lombok.Data;
import java.util.List;

@Data
public class AnnotationMapping {
    private List<Mapping> mappings;

    @Data
    public static class Mapping {
        private String annotationName; // e.g., "SlingServletPaths"
        private String nodeType;       // e.g., "OSGI_COMPONENT" - type of target node
        private String attributeName;  // e.g., "paths"
        private String relationship;   // e.g., "PROVIDES"
        private String idPrefix;       // e.g., "path:"
        private String classType;      // e.g., "SLING_SERVLET" - changes the class's NodeType when annotation present
    }
}
