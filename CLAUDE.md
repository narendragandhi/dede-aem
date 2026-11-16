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
- `src/main/resources/forbidden-apis.json` - Cloud violation rules (24 categories)
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

Run `--help` for the full list (source of truth: `cli/CliOptions.java`, which parses and
validates every flag, and `printHelp()` in `DedeApplication.java`). Most used:

```
--profiles <p1,p2>  Analysis profiles (default: aem)
--json <path>       Findings + graph stats + suggestions as JSON
--sarif <path>      Forbidden-API findings as SARIF 2.1.0
--fail-on-critical  Exit 1 on any CRITICAL finding (CI gate)
--fail-on <level>   Exit 1 on any finding at <level> or above (HIGH, MEDIUM, ...)
--runtime-traces <f>  Join OpenTelemetry traces (OTLP JSON) to the graph
--emit-otel-config <f> Write an OTel Java agent config from the graph
--extended-checks   Add OakPal content-package, legacy-path, service-user checks
--dot <path.dot>    Export dependency graph to DOT format
--security          Enable security surface audit
--snapshot <dir>    Save graph snapshot
--compare <file>    Compare with previous snapshot
```

Runtime overlay lives in `runtime/`: OtlpTraceReader, RuntimeOverlay, OtelConfigEmitter, and
request attribution (RequestLogReader, ContentIndex, RequestAttribution), and error.log
attribution (ErrorLogReader, ErrorAttribution). Flags: `--runtime-traces`, `--emit-otel-config`,
`--otel-config`, `--otel-max-classes`, `--request-log`, `--error-log`.

`--mcp` serves a finished scan over stdio (`mcp/McpServer`: hand-rolled JSON-RPC, tools only;
`mcp/DedeTools`: the tools). `main()` swaps `System.out` for stderr in that mode -- anything
printed to the saved stdout breaks the protocol.

A scan runs in stages (`DedeApplication.runScan`): static scan, static analyses, findings,
the runtime join (`cli/RuntimeJoin`, which writes `runtime.*` onto the graph), then everything
that reads runtime data (snapshots, blast radius, reports, MCP). Metric names live only in
`domain/model/RuntimeMetrics`; resource-type node ids only come from
`domain/model/ResourceTypes.nodeId()` (one `res:` scheme).

Default scan exclusions live in `discovery/ScanExclusions.java`.

All gates and reports read one set of findings: `cloud/CloudFindings` (catalog scan +
`CloudReadinessAnalyzer` graph checks + opt-in `cloud/ExtendedChecks`). Add new checks there,
not as a parallel report. After any rule change, re-run against WKND, aem-core-wcm-components
and ACS AEM Commons and inspect every new CRITICAL/HIGH: that is how the false positives in
this repo were found.

## Testing

```bash
# Run all tests
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
