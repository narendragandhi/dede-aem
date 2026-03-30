package com.dede.aem.models;

/**
 * Types of relationships between nodes in the architectural graph.
 */
public enum RelationshipType {
    // Code relationships
    CALLS,
    IMPLEMENTS,
    EXTENDS,
    IMPORTS,
    USES,
    CONTAINS,

    // OSGi relationships
    PROVIDES,
    CONSUMES,
    WIRES_TO,
    CONFIGURES,

    // Content relationships
    INSTANTIATED_BY,
    REFERENCES,
    ADAPTS_TO,
    DYNAMIC_ADAPTS_TO,

    // HTL relationships
    INCLUDES,
    DEFINES,

    // Analysis
    DEPRECATED_BY,
    REPLACED_BY
}
