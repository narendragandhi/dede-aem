# Dede-Java Architecture

## Overview

Dede-Java is an **Architectural Intelligence Engine** for AEM (Adobe Experience Manager) codebases. It builds a dependency graph of code artifacts and analyzes them for cloud readiness, security issues, and architectural problems.

## System Design Principles

The system follows **Hexagonal Architecture (Ports & Adapters)** to ensure scalability and maintainability.

- **Domain Core**: JGraphT-based Directed Multigraph modeling `CodeNode` and `Relationship`
- **Ports (Interfaces)**:
    - `MetadataCache`: Persistence port
    - `SourceParser`: Analysis port
- **Adapters (Implementations)**:
    - `JsonMetadataCache`: Local JSON persistence
    - `OsgiManifestParser`, `OsgiServiceParser`: Specialized OSGi metadata extractors

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              CLI / Web Interface                             │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐ │
│  │   CLI Args   │  │   REST API   │  │  GraphQL API │  │     Web UI       │ │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  └────────┬─────────┘ │
└─────────┼─────────────────┼─────────────────┼───────────────────┼───────────┘
          │                 │                 │                   │
          ▼                 ▼                 ▼                   ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                            DedeApplication                                   │
│                         (Spring Boot Main Entry)                             │
└─────────────────────────────────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Analysis Engine                                 │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │                         DedeJavaScanner                                 │ │
│  │                    (Orchestrates all parsers)                           │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
│           │                    │                    │                        │
│           ▼                    ▼                    ▼                        │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐             │
│  │  Java Parsers   │  │ Content Parsers │  │ Config Parsers  │             │
│  │                 │  │                 │  │                 │             │
│  │ • OsgiScanner   │  │ • JcrContent    │  │ • Dispatcher    │             │
│  │ • SlingServlet  │  │ • ContentPkg    │  │ • ClientLib     │             │
│  │ • SlingModel    │  │ • HtlParser     │  │ • OakPal        │             │
│  │ • ForbiddenApi  │  │ • CndParser     │  │                 │             │
│  └─────────────────┘  └─────────────────┘  └─────────────────┘             │
└─────────────────────────────────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Domain Layer                                    │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │                          GraphService                                   │ │
│  │              (In-memory graph of nodes and relationships)               │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌────────────────┐  │
│  │   CodeNode   │  │ Relationship │  │   NodeType   │  │ RelationshipType│ │
│  └──────────────┘  └──────────────┘  └──────────────┘  └────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           Analysis Modules                                   │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐             │
│  │ Cloud Readiness │  │    Security     │  │   BPA Report    │             │
│  │                 │  │                 │  │                 │             │
│  │ • CST Rules     │  │ • OWASP Top 10  │  │ • HTML Export   │             │
│  │ • Path Analysis │  │ • Injection     │  │ • CST Mapping   │             │
│  │ • API Compat    │  │ • Auth Issues   │  │ • Remediation   │             │
│  └─────────────────┘  └─────────────────┘  └─────────────────┘             │
└─────────────────────────────────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Output Layer                                    │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐│
│  │    JSON     │  │     DOT     │  │    HTML     │  │      Web UI         ││
│  │   Export    │  │  (Graphviz) │  │ (BPA Report)│  │   (D3.js Graph)     ││
│  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────────────┘│
└─────────────────────────────────────────────────────────────────────────────┘
```

## Data Flow & Scalability

To handle 1000+ bundles without memory exhaustion:

1. **Discovery Phase**: `ProjectScanner` crawls the root
2. **Filter Phase**:
    - Source files → `SourceParser` (Deep AST)
    - Local Manifests → `OsgiManifestParser`
    - External JARs → `JarScanner` → `MetadataCache` lookup
3. **Cache Logic**: If JAR exists in `.dede-cache.json` and hasn't changed, metadata loads directly, skipping Zip I/O and XML parsing
4. **Wiring Phase**: `OsgiLinker` creates `WIRES_TO` edges between bundles

## Core Components

### 1. Entry Points

| Component | Purpose |
|-----------|---------|
| **CLI** (DedeApplication.java) | Command-line analysis |
| **REST API** (GraphController.java) | RESTful endpoints |
| **GraphQL API** | Query nodes and relationships |
| **Web UI** | D3.js graph visualization |

### 2. Discovery Layer (Parsers)

| Parser | Purpose | File Types |
|--------|---------|------------|
| `OsgiComponentScanner` | OSGi DS annotations | .java |
| `SlingServletParser` | Servlet registrations | .java |
| `SlingModelParser` | Sling Model annotations | .java |
| `SlingHtlParser` | HTL template analysis | .html |
| `JcrContentParser` | JCR content structure | .content.xml |
| `ClientLibParser` | Client library deps | .content.xml |
| `ContentPackageScanner` | Package validation | jcr_root/ |
| `DispatcherConfigParser` | Dispatcher rules | dispatcher.any |
| `ForbiddenApiScanner` | Deprecated API usage | .java |

### 3. Domain Model

#### CodeNode
```java
public class CodeNode {
    String id;           // Unique identifier
    String name;         // Display name
    NodeType type;       // Classification
    String description;  // Human-readable description
    String sourcePath;   // File location
    Map<String, String> properties;  // Additional metadata
}
```

#### NodeType
- `OSGI_COMPONENT` - OSGi service components
- `SLING_SERVLET` - Sling servlets
- `SLING_MODEL` - Sling models
- `HTL_TEMPLATE` - HTL templates
- `CLIENT_LIBRARY` - Client libraries
- `JCR_COMPONENT` - JCR content nodes
- `CONTENT_PACKAGE` - Content packages
- `VULNERABILITY` - Security/cloud issues
- `DISPATCHER_RULE` - Dispatcher configurations

#### RelationshipType
- `DEPENDS_ON` - Direct dependency
- `USES` - Runtime usage
- `EXTENDS` - Inheritance
- `CONTAINS` - Composition
- `REFERENCES` - Content reference
- `VIOLATES` - Rule violation

### 4. Analysis Modules

| Module | Purpose |
|--------|---------|
| **Cloud Readiness** | AEM Cloud Service compatibility |
| **Security** | OWASP vulnerability detection |
| **BPA Report** | Adobe BPA-compatible reports |

## Technology Stack

- **Java 21** - Virtual Threads and Records
- **Spring Boot 3.2** - Web framework
- **JavaParser 3.25** - AST-based code analysis
- **JGraphT 1.5** - Graph algorithms
- **OakPal 2.3** - AEM content package validation
- **D3.js** - Graph visualization
- **Docker** - Containerization

## Extension Points

### Adding a New Parser

1. Create class implementing analysis logic
2. Inject `GraphService`
3. Create `CodeNode` objects for detected artifacts
4. Create `Relationship` objects for dependencies
5. Register in `DedeJavaScanner`

### Adding Cloud Rules

1. Add pattern to `forbidden-apis.json`
2. Include CST rule ID for BPA mapping
3. Provide remediation guidance

### Adding Security Rules

1. Add detection logic to `ForbiddenApiScanner`
2. Create `VULNERABILITY` nodes
3. Map to OWASP category

## Security

- XML parsing hardened against XXE
- No external network calls during analysis
- Input validation on all APIs
- No secrets in source code

## ADRs (Architectural Decision Records)

- `ADR 001`: External Artifact Metadata Caching (Accepted)
