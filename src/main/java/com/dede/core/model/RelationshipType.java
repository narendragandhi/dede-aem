package com.dede.core.model;

public enum RelationshipType {
    CONTAINS,   // Project -> Package -> Class
    DECLARES,   // Class -> Method
    CALLS,      // Method -> Method
    INHERITS,   // Class -> Class
    IMPLEMENTS, // Class -> Interface
    USES,        // Method -> Field
    EXPORTS,     // Bundle -> Package
    IMPORTS,     // Bundle -> Package
    PROVIDES,    // Bundle -> Service
    CONSUMES,    // Component -> Service
    CONFIG_BY,   // Component -> OSGi Config
    WIRES_TO,     // Bundle -> Bundle
    FRAGMENTS_TO, // Fragment Bundle -> Host Bundle
    ADAPTS_TO,    // JCR Resource Type -> Sling Model
    REFERENCES,   // JCR Resource Type -> JCR Resource Type
    DEPENDS_ON,   // ClientLib -> ClientLib
    EMBEDS        // ClientLib -> ClientLib
}
