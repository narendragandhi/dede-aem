# Architecture Document - dede-java

## 1. Modern System Design Principles
The system follows **Hexagonal Architecture (Ports & Adapters)** to ensure scalability and maintainability.

- **Domain Core**: JGraphT-based Directed Multigraph modeling `CodeNode` and `Relationship`.
- **Ports (Interfaces)**: 
    - `MetadataCache`: Persistence port.
    - `SourceParser`: Analysis port.
- **Adapters (Implementations)**:
    - `JsonMetadataCache`: Local JSON persistence.
    - `OsgiManifestParser`, `OsgiServiceParser`: Specialized OSGi metadata extractors.

## 2. Data Flow & Scalability
To handle 1000+ bundles without memory exhaustion:

1.  **Discovery Phase**: `ProjectScanner` crawls the root.
2.  **Filter Phase**: 
    - Source files -> `SourceParser` (Deep AST).
    - Local Manifests -> `OsgiManifestParser`.
    - External JARs -> `JarScanner` -> `MetadataCache` lookup.
3.  **Cache Logic**: If JAR exists in `.dede-cache.json` and hasn't changed, the manifest/service metadata is loaded directly into the graph, skipping Zip I/O and XML parsing.
4.  **Wiring Phase**: `OsgiLinker` performs a post-scan pass to create `WIRES_TO` edges between bundles based on the unified metadata.

## 3. Technology Stack
- **Framework**: Spring Boot 3.2 (Virtual Thread ready).
- **AST Analysis**: JavaParser (Lightweight AST).
- **Graph Engine**: JGraphT.
- **Persistence**: Jackson (JSON Cache).
- **Agent Orchestration**: Embabel (GOAP Planner).
- **Local LLM**: Ollama (Mistral/CodeLlama).

## 4. ADRs (Architectural Decision Records)
- `ADR 001`: External Artifact Metadata Caching (Accepted).
