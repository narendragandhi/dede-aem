# Product Requirements Document (PRD) - dede-java

## 1. Introduction
**dede-java** is a high-performance static analysis and dependency exploration tool for Java ecosystems. It maps complex inter-dependencies in multi-bundle OSGi environments (e.g., AEM, Karaf) and standard Java projects, providing "blast radius" analysis for architectural changes.

## 2. Problem Statement
Large-scale Java products (1000+ bundles) suffer from "Dependency Blindness." Changes in a core bundle or service can have unknown ripple effects across bundle boundaries, leading to runtime failures in OSGi containers that are difficult to debug.

## 3. Goals & Objectives
- **Hybrid Discovery**: Scan local source code (White Box) and external binary dependencies (Grey Box) simultaneously.
- **OSGi Awareness**: Explicitly model OSGi BUNDLEs, PACKAGE exports/imports, and Declarative Services (DS) consumption/provision.
- **Architectural Scalability**: Handle 1000+ external JARs with sub-second subsequent scan times using intelligent caching.
- **Agentic reasoning**: Enable AI-driven architectural queries (powered by Embabel/Ollama) to perform impact analysis in natural language.

## 4. Core Features
1.  **Scanner Engine**: Recursive crawler for `.java`, `MANIFEST.MF`, `.jar`, and `OSGI-INF/*.xml` files.
2.  **OSGi Linker**: Logic to wire bundles based on package imports/exports and service references.
3.  **Metadata Cache**: O(1) skip-logic for external artifacts using path/size/timestamp validation.
4.  **Impact Graph**: In-memory Directed Multigraph (JGraphT) for complex pathfinding.
5.  **Agent Skills**: Deterministic GOAP-based agent actions for querying the graph.

## 5. Non-Functional Requirements
- **Performance**: Zero-load parsing for cached external dependencies.
- **Privacy**: 100% local analysis (Local LLM via Ollama).
- **Architecture**: Hexagonal/Clean Architecture for swap-able persistence and analysis adapters.
