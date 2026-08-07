# Changelog

All notable changes to Dede-Java will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **CI self-scan uploaded to GitHub Code Scanning** - every push/PR now runs `--sarif` against dede-java's own source (reusing the jar the build job already produced) and uploads results via `github/codeql-action/upload-sarif`, so the next regression this session's pattern would predict shows up as a CI annotation instead of requiring someone to manually notice it.
- **`docs/PERFORMANCE.md`** - measured scan performance across three real AEM codebases up to 1,433 files (sub-linear time growth, flat ~260-300MB memory), plus a reproducible benchmark (`VulnerabilityServiceBenchmark`, run via `-Dtest=VulnerabilityServiceBenchmark`) that found the `--security` reachability audit scales super-linearly at pessimistic CVE/endpoint counts (20x more work took 74x longer) -- a real, evidence-backed finding, not yet fixed.
- **`POST /api/graph/scan`** - Populates the graph in server mode via the API instead of only via CLI args. Fails closed by default (`DEDE_SCAN_ALLOWED_ROOT` required) since this API has no authentication; without a restriction any caller who could reach the endpoint could direct the server to walk an arbitrary filesystem path -- confirmed directly against a running server before adding the check. See [ADR 007](docs/adr/0007-scan-endpoint-fails-closed-on-path.md).
- **Maven Plugin** (`dede-maven-plugin`) - Runs Dede as a real `mvn verify` build step, failing on CRITICAL cloud-readiness issues. See [ADR 006](docs/adr/0006-maven-plugin-headless-spring-bootstrap.md).
- **CVE Blast-Radius Prioritization** - Import an OWASP Dependency-Check JSON report (`--dependency-check-report <path>`) and rank real CVEs by how many public endpoints they're actually reachable from, not just CVSS score. See [ADR 004](docs/adr/0004-cve-data-via-dependency-check-import.md).
- **SARIF 2.1.0 CLI Output** - `--sarif <path>` exports forbidden-API findings in the format GitHub Code Scanning, VS Code's Problems panel, and most SIEMs consume natively. The serializer already backed the REST API; it just had no CLI flag.
- **Dependabot** - Weekly dependency updates across both Maven modules, GitHub Actions, and Docker base images.
- **BPA Comparison Report Generator** - Generate Adobe BPA-compatible HTML reports with Cloud Manager CST rule mappings
- **Dispatcher Configuration Analysis** - Parse dispatcher.any files, detect security anti-patterns, map filter rules to servlets
- **Enhanced HTL Template Analysis** - Full HTL expression parsing including data-sly-test, data-sly-list, data-sly-template, and embedded JavaScript
- **Content Package Deep Scanner** - Scan unpacked jcr_root content for ACL issues, hardcoded paths, and cloud-incompatible features
- **Web UI Enhancements**:
  - Impact analysis showing affected nodes when selecting a component
  - Path finding to discover dependency chains between nodes
  - Severity filters for violation nodes
  - Export to JSON/CSV
  - Keyboard shortcuts (/, Esc, arrow keys)
  - Dark/light theme toggle
  - Violation count badges on nodes
- **Docker Support** - Multi-platform Docker images (amd64/arm64) published to GHCR
- **GitHub Actions Workflow** - Example workflow for AEM projects to integrate Dede analysis

### Fixed

- **CLI never exited** - `spring-boot-starter-web` meant `SpringApplication.run()` always booted an embedded Tomcat server that nothing ever shut down. Every CLI invocation, including `--help` and a completed one-shot scan, hung forever instead of returning control to the shell. No-args mode still correctly stays up as the REST/GraphQL/Web UI server; `--watch` mode still stays alive by design.
- **Docker entrypoint silently dropped all CLI arguments** - `ENTRYPOINT ["sh", "-c", "java ... $@"]` has a classic shell bug: the first argument Docker appends becomes `sh -c`'s `$0`, not part of `$@`. `docker run image --help` ran the app with zero arguments instead. Fixed with the standard `"--"` placeholder pattern. This is what caused `Docker Build & Push`'s image smoke test to hang for 6 hours and get force-cancelled, even after the CLI-exit fix above.
- **Core scanner silently blind on Java 14+ syntax** - `SourceParser` (the class/graph builder used on every scan), `ForbiddenApiScanner`, `SlingJobParser`, and `ServletSecurityAuditor` all constructed `JavaParser` with a default language level that rejects records, switch expressions, pattern-matching `instanceof`, text blocks, and sealed classes -- most Java 14-21 syntax, i.e. most real AEM Cloud Service code, including Dede's own. Failures were silent (DEBUG-level log, no exception). Fixed by configuring `BLEEDING_EDGE`. See [ADR 005](docs/adr/0005-javaparser-bleeding-edge-language-level.md).
- **Security reachability audit checked Dijkstra path in the wrong direction** - `VulnerabilityService.audit()` checked `dijkstra.getPath(endpoint, vuln)`, but every `EXPOSES` edge is created as `vuln -> affectedNode`. In a directed graph, nothing pointed *into* a vulnerability node, so the check could only ever return no path. Confirmed empirically with a standalone JGraphT reproduction before fixing. This means the reachability audit had likely never produced a single real finding since it was written. Fixed by checking `getPath(vuln, endpoint)`; added a permanent regression test.
- **Profile mappings silently empty on every real-world invocation** - `SourceParser.loadProfiles()` runs on every scan (CLI, Docker, Maven plugin) and always clears `activeMappings` first, then looked up the profile via a filesystem path only (`profiles/<name>.json` relative to the current working directory) -- no classpath fallback, unlike the constructor's initial `loadDefaultProfile()`. Since real consumers never run from `dede-java`'s own source checkout (the only place that file exists on disk), every real invocation of the packaged jar, Docker image, or the new Maven plugin silently loaded zero mappings, breaking the `@Model`/`@Component`/`@Reference`/`@SlingServletResourceTypes` relationship-building this method drives -- with only a WARN log to notice by. Fixed by adding the same classpath fallback `loadDefaultProfile()` already had; added a regression test using a profile that exists only on the classpath, since the real filesystem-miss condition can't be reproduced by changing `user.dir` at runtime (confirmed empirically: `File` resolves relative paths against the JVM's actual startup working directory, not the `user.dir` system property).
- **SARIF export threw `NullPointerException` on any violation with a mapped CWE** - `SarifReport.buildRule()` built a `Map.of("guid", null, ...)`; `Map.of()` forbids null values and throws immediately. Only surfaced when the Maven plugin's SARIF export hit a violation with a non-null `cweId()` for the first time -- no existing test exercised that branch. Fixed by omitting the optional `guid` key instead of representing it as null.
- **No-args server mode had a permanently empty graph** - nothing calls `scanner.scan()` unless the process is launched with a CLI project-path argument, and no REST/GraphQL endpoint existed to trigger one. Confirmed by starting a real no-args server and hitting every endpoint -- all returned `nodeCount: 0`. Fixed by adding `POST /api/graph/scan` (see Added, and [ADR 007](docs/adr/0007-scan-endpoint-fails-closed-on-path.md) for the fail-closed path restriction that came with it).
- **Unmapped REST routes returned 500 instead of 404** - `GlobalExceptionHandler`'s `@ExceptionHandler(Exception.class)` catch-all also caught Spring's own `NoResourceFoundException` (its signal for "no route matched"), turning every wrong-URL request into a fake "Internal Server Error." Added a specific handler for it that returns a proper 404.

### Changed

- **Reachability findings now ranked by blast radius**, not just reachable/not-reachable - `VulnerabilityService` now counts the distinct public endpoints (including `SLING_SERVLET` nodes, previously not checked) each vulnerability is reachable from and sorts by that count, CVSS as tiebreaker.
- Improved XML parser security hardening (disabled external entities)
- Enhanced GraphQL schema with new node types

### Security

- **Fixed XXE (CWE-611) in 4 of 8 XML parsing sites** - `OsgiServiceParser` (x2), `SlingClientLibParser`, `SlingResourceParser` had no hardening against external entity resolution. All 8 sites (including 4 that already had ad hoc hardening, now deduplicated) go through a single `XmlSecurity.newSafeDocumentBuilderFactory()`. See [ADR 003](docs/adr/0003-centralized-xxe-hardening.md).
- **Fixed reflected XSS** - `GET /api/analysis/bpa-report/html?projectName=<script>...` on an unauthenticated endpoint flowed the `projectName` request parameter unescaped into the generated HTML report. Fixed in both `AnalysisController` and `BpaReportGenerator` (which have separate, duplicated `generateHtmlReport` methods).
- **Fixed 3 ReDoS-prone regexes** in `SlingHtlParser` (`SLY_TEST`/`LIST`/`REPEAT` patterns) where a trailing `\s*` and a preceding lazy `[^}]+?` both matched the same whitespace, causing pathological backtracking on malformed input. Fixed with possessive quantifiers. 5 other flagged regexes were manually reviewed and are not vulnerable (no nested/overlapping quantifiers) -- deferred, not blindly "fixed."
- **CI security gates are now real** - `mvn spotbugs:check` and OWASP Dependency-Check were wired with `|| true` / `continue-on-error: true`, so neither could ever fail the build regardless of findings. See [ADR 002](docs/adr/0002-security-gate-scoped-to-findsecbugs-category.md).
- Added secure XML parsing configuration to prevent XXE attacks
- Security rule catalog expanded with OWASP patterns

## [1.0.0] - Initial Release

### Added

- **Code Graph Analysis** - Build dependency graph from AEM/Java codebase
- **OSGi Component Detection** - Detect @Component, @Service, SCR annotations
- **Sling Servlet Analysis** - Parse servlet registrations and resource types
- **Sling Model Scanning** - Detect @Model annotations and adaptables
- **JCR Content Parsing** - Parse .content.xml files and detect node types
- **Cloud Readiness Checking** - 70+ rules for AEM as a Cloud Service compatibility
- **Security Scanning** - Detect injection vulnerabilities, dangerous APIs
- **Forbidden API Detection** - Configurable catalog of deprecated/forbidden patterns
- **GraphQL API** - Query the code graph via GraphQL
- **REST API** - RESTful endpoints for analysis
- **Web UI** - D3.js-based interactive graph visualization
- **DOT Export** - Export graph to Graphviz DOT format
- **CLI Interface** - Command-line analysis with multiple output formats

### Supported AEM Patterns

- OSGi Declarative Services (@Component, @Activate, @Reference)
- Felix SCR annotations (legacy support)
- Sling Models (@Model, @Inject, @ChildResource)
- Sling Servlets (paths, resource types, selectors)
- HTL/Sightly templates (data-sly-use, data-sly-resource)
- Client Libraries (categories, dependencies, embeds)
- Content packages (filter.xml, .content.xml)
- Workflow definitions
- Replication agents
- Custom node types (.cnd)

### Cloud Readiness Rules

Categories covered:
- Mutable content paths
- Deprecated APIs
- Repository structure
- Index definitions
- Replication configuration
- Workflow compatibility
- Authentication patterns
- Session handling

---

## Version History

| Version | Date | Highlights |
|---------|------|------------|
| 1.0.0 | TBD | Initial release with core analysis |
| 1.1.0 | TBD | BPA reports, dispatcher analysis, enhanced UI |
