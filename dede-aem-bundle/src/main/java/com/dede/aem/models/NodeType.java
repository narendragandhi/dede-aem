package com.dede.aem.models;

/**
 * Types of nodes that can exist in the architectural graph.
 */
public enum NodeType {
    // Code Structure
    PACKAGE,
    CLASS,
    INTERFACE,
    METHOD,
    FIELD,

    // OSGi
    BUNDLE,
    OSGI_SERVICE,
    OSGI_COMPONENT,
    OSGI_CONFIG,

    // Sling
    SLING_MODEL,
    SLING_SERVLET,
    SLING_FILTER,

    // JCR/Content
    JCR_NODE,
    JCR_COMPONENT,
    JCR_RESOURCE_TYPE,
    JCR_TEMPLATE,

    // HTL/Frontend
    HTL_FILE,
    CLIENTLIB,

    // Dispatcher
    DISPATCHER_FILTER,
    DISPATCHER_RULE,

    // Cloud
    CDN_RULE,
    REPLICATION_AGENT,

    // Issues
    VULNERABILITY,
    DEPRECATED_API
}
