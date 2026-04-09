package com.dede.domain.model;

public enum RelationshipType {
    CONTAINS,   // Project -> Package -> Class
    DECLARES,   // Class -> Method
    CALLS,      // Method -> Method
    INHERITS,   // Class -> Class (legacy, use EXTENDS)
    EXTENDS,    // Class -> Superclass
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
    DYNAMIC_ADAPTS_TO, // Code -> Model (via adaptTo)
    PACKAGES,          // Content Package -> JCR Path
    VIOLATES,          // Package Violation -> Content Package

    // Sling Request Processing relationships
    HANDLES_RESOURCE_TYPE, // Servlet -> Resource Type it handles
    HANDLES_PATH,          // Servlet -> Path it handles
    HANDLES_EXTENSION,     // Servlet -> Extension it handles
    HANDLES_SELECTOR,      // Servlet -> Selector it handles
    FILTERS_SCOPE,         // Filter -> Scope (REQUEST/COMPONENT/ERROR)
    INJECTS_CHILD,         // Model -> Child resource injection
    INJECTS_VALUE,         // Model -> ValueMap injection
    INJECTS_REQUEST,       // Model -> Request attribute injection
    EXTENDS_RESOURCE_TYPE, // Resource Type -> Super Resource Type

    // Sling Job relationships
    PROCESSES,   // JobConsumer/WorkflowProcess -> SlingJob topic (consumes jobs)
    PRODUCES     // Component -> SlingJob topic (enqueues jobs via JobManager)
}
