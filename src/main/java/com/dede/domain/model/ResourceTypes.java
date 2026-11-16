package com.dede.domain.model;

/**
 * The one node id scheme for Sling resource types. Component folders, {@code @Model(resourceType)},
 * {@code @SlingServletResourceTypes}, HTL and content usage all describe the same resource type,
 * so they must land on the same node; two schemes ({@code res:} and {@code resType:}) used to split
 * each type in two, and every consumer had to know both.
 */
public final class ResourceTypes {

    public static final String PREFIX = "res:";

    private ResourceTypes() {}

    public static String nodeId(String resourceType) {
        return PREFIX + resourceType;
    }

    public static boolean isResourceType(CodeNode node) {
        return node.getId().startsWith(PREFIX);
    }
}
