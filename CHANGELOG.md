# Changelog

All notable changes to Dede-Java will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

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

### Changed

- Improved XML parser security hardening (disabled external entities)
- Enhanced GraphQL schema with new node types

### Security

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
