package com.dede.domain.model;

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
    EMBEDS,       // ClientLib -> ClientLib
    EXPOSES,      // Vulnerability -> Bundle/ClientLib
    CONFIGURES,   // JCR Node -> OSGi/CAConfig
    INSTANTIATED_BY, // JCR Node -> JCR Resource Type
    DEFINES,       // ui.apps folder -> JCR Resource Type
    DYNAMIC_CONSUMES, // Code -> OSGi Service (via BundleContext)
    DYNAMIC_ADAPTS_TO // Code -> Model (via adaptTo)
}
