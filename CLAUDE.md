# Dede-Java Project Guide

## Overview

Dede-Java is an **Architectural Intelligence Engine** for analyzing AEM (Adobe Experience Manager) and OSGi-based codebases. It provides static analysis and dependency graph generation for cloud readiness validation without requiring a running AEM instance.

## Quick Start

```bash
# Build
mvn clean package -DskipTests

# Run CLI (scan a project)
java -jar target/dede-java-0.0.1-SNAPSHOT-exec.jar /path/to/aem-project

# Run as web server
java -jar target/dede-java-0.0.1-SNAPSHOT-exec.jar

# Run tests
mvn test
```

## Project Structure

```
src/main/java/com/dede/
├── api/              # REST API controllers (GraphController)
├── analysis/         # Source parsing & analysis
├── cloud/            # Cloud readiness analysis (ForbiddenApiScanner, OakPalValidator)
├── config/           # Application configuration
├── discovery/        # Project scanning & parsing (ProjectScanner, SourceParser)
├── domain/           # Domain models & services (GraphService, CodeNode)
├── exception/        # Exception handling
├── intelligence/     # AI insights & vulnerability analysis
├── knowledge/        # Knowledge base (GovernanceEngine)
├── osgi/             # OSGi-specific analysis
└── security/         # Security analysis
```

## Key Files

- `pom.xml` - Maven configuration (Java 21, Spring Boot 3.2.0)
- `src/main/resources/application.yml` - Server config (port 8080)
- `src/main/resources/forbidden-apis.json` - Cloud violation rules (25 categories)
- `profiles/aem.json` - AEM annotation mappings
- `profiles/osgi.json` - OSGi annotation mappings

## API Endpoints

| Endpoint | Description |
|----------|-------------|
| `GET /api/graph` | Full dependency graph |
| `GET /api/graph/stats` | Node and edge counts |
| `GET /api/graph/nodes/{id}` | Single node details |
| `GET /api/graph/cycles` | Circular dependency detection |
| `GET /actuator/health` | Health check |
| `GET /swagger-ui.html` | Swagger UI |
| `GET /graphql` | GraphQL endpoint |
| `GET /graphiql` | GraphQL explorer |

## CLI Options

```
--profiles <p1,p2>  Analysis profiles (default: aem)
--dot <path.dot>    Export dependency graph to DOT format
--security          Enable security surface audit
--snapshot <dir>    Save graph snapshot
--compare <file>    Compare with previous snapshot
```

## Testing

```bash
# Run all tests (61 tests)
mvn test

# Run specific test class
mvn test -Dtest=GraphServiceTest

# Run with coverage
mvn test jacoco:report
```

## Docker

```bash
# Build and run with Docker Compose
docker-compose up

# Or build manually
docker build -t dede-java .
docker run -p 8080:8080 -v ./scan:/scan:ro dede-java
```

## Dependencies

- **Java 21** - Required for Virtual Threads and Records
- **Spring Boot 3.2.0** - Web framework
- **JavaParser 3.25.7** - AST-based code analysis
- **JGraphT 1.5.2** - Graph algorithms
- **OakPal 2.3.0** - AEM content package validation

## Common Tasks

### Adding a new cloud violation rule
Edit `src/main/resources/forbidden-apis.json`

### Adding a new annotation mapping
Edit `profiles/aem.json` or create a new profile in `profiles/`

### Viewing the dependency graph
1. Run: `java -jar dede.jar /project --dot graph.dot`
2. Render: `dot -Tpng graph.dot -o graph.png`
