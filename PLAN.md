# Dede-Java Enhancement Plan

## Objective
Build competitive moat and production readiness for dede-java.

---

## PHASE 1: COMPETITIVE MOAT (Differentiation Features)

### 1.1 Enhanced HTL Template Analysis
**Current State**: Basic regex parsing of `data-sly-use` only
**Enhancement**: Deep HTL/Sightly analysis with full expression parsing

**Tasks**:
- [ ] Add HTL expression parser (conditionals, lists, templates)
- [ ] Detect `data-sly-test`, `data-sly-list`, `data-sly-repeat` usage
- [ ] Parse `data-sly-template` definitions and `data-sly-call` invocations
- [ ] Extract embedded JavaScript expressions `${...}`
- [ ] Link HTL to clientlib dependencies (CSS/JS)
- [ ] Create HTL_TEMPLATE and HTL_EXPRESSION node types

**Files to modify**:
- `SlingHtlParser.java` - Expand regex patterns and parsing logic
- `NodeType.java` - Add HTL_TEMPLATE, HTL_EXPRESSION types
- `schema.graphqls` - Add new types to GraphQL schema

---

### 1.2 Dispatcher Configuration Analysis
**Current State**: Not analyzed
**Enhancement**: Parse dispatcher configs for path mappings and security rules

**Tasks**:
- [ ] Create `DispatcherConfigParser.java`
- [ ] Parse `dispatcher.any` files for farm definitions
- [ ] Extract `/filter` rules and map to servlets/paths
- [ ] Detect security anti-patterns (missing deny rules)
- [ ] Link dispatcher paths to SLING_SERVLET nodes
- [ ] Parse rewrite rules and URL mappings

**Files to create/modify**:
- `src/main/java/com/dede/discovery/DispatcherConfigParser.java` (new)
- `NodeType.java` - Add DISPATCHER_RULE, DISPATCHER_FILTER
- `forbidden-apis.json` - Add dispatcher security rules

---

### 1.3 BPA Comparison Report
**Current State**: No mapping to Adobe BPA output
**Enhancement**: Generate BPA-compatible findings with CST rule IDs

**Tasks**:
- [ ] Create `BpaReportGenerator.java`
- [ ] Map dede violations to Cloud Manager CST codes (CST-1, CST-2, etc.)
- [ ] Generate HTML report styled like BPA output
- [ ] Add `--bpa-report <path>` CLI option
- [ ] Create REST endpoint `/api/bpa-report`

**Files to create/modify**:
- `src/main/java/com/dede/cloud/BpaReportGenerator.java` (new)
- `DedeApplication.java` - Add CLI option
- `GraphController.java` - Add endpoint
- `forbidden-apis.json` - Ensure all rules have cloudManagerRule IDs

---

### 1.4 Content Package Deep Scanning
**Current State**: OakPal validates packages but requires built .zip files
**Enhancement**: Scan unpacked content directly from source

**Tasks**:
- [ ] Create `ContentPackageScanner.java` for jcr_root analysis
- [ ] Parse filter.xml to understand package scope
- [ ] Detect ACL issues directly from `.content.xml` rep:policy nodes
- [ ] Find hardcoded paths in content (dam:, etc:, apps:)
- [ ] Validate node type definitions (.cnd files)
- [ ] Check for cloud-incompatible features (vanity URLs, workflows)

**Files to create/modify**:
- `src/main/java/com/dede/discovery/ContentPackageScanner.java` (new)
- `JcrContentParser.java` - Enhance ACL detection
- `ForbiddenApiCatalog.java` - Add content-level rules

---

### 1.5 Web UI Enhancements
**Current State**: D3.js graph works but limited interaction
**Enhancement**: Add impact analysis, path finding, and filtering in UI

**Tasks**:
- [ ] Add "Impact Analysis" button showing affected nodes
- [ ] Add "Find Path" modal to discover dependency chains
- [ ] Add severity filter for violation nodes
- [ ] Add export to JSON/CSV for filtered results
- [ ] Add keyboard shortcuts (/, Esc, arrow keys)
- [ ] Add dark/light theme toggle
- [ ] Show violation count badges on nodes

**Files to modify**:
- `static/index.html` - Add new UI features

---

## PHASE 2: PRODUCTION ADOPTION

### 2.1 Docker Hub / GHCR Publishing
**Current State**: Docker builds locally but not published
**Enhancement**: Auto-publish to GitHub Container Registry

**Tasks**:
- [ ] Update CI workflow to push to ghcr.io
- [ ] Add semantic versioning tags (latest, v1.0.0)
- [ ] Add multi-platform builds (amd64, arm64)
- [ ] Create docker-compose.yml for easy local usage
- [ ] Add usage examples in README

**Files to modify**:
- `.github/workflows/ci.yml` - Add GHCR push
- `README.md` - Add docker pull instructions

---

### 2.2 GitHub Actions Sample Workflow
**Current State**: CI exists but no reusable action for users
**Enhancement**: Provide copy-paste workflow for AEM projects

**Tasks**:
- [ ] Create `.github/workflows/dede-example.yml`
- [ ] Document integration in README
- [ ] Add badge generation for repo status
- [ ] Add PR comment with scan results

**Files to create**:
- `examples/github-workflow.yml` (new)

---

### 2.3 Documentation Improvements
**Current State**: README exists but missing visuals and examples
**Enhancement**: Add screenshots, sample outputs, and architecture diagrams

**Tasks**:
- [ ] Add screenshot of web UI to README
- [ ] Create architecture diagram (mermaid or PNG)
- [ ] Add sample JSON output snippets
- [ ] Create CONTRIBUTING.md
- [ ] Create CHANGELOG.md
- [ ] Add API examples with curl commands

**Files to create/modify**:
- `docs/screenshots/` (new directory)
- `docs/architecture.md` (new)
- `CONTRIBUTING.md` (new)
- `CHANGELOG.md` (new)
- `README.md` - Add images and examples

---

### 2.4 Maven Central Publishing (Future)
**Current State**: Not published
**Enhancement**: Prepare for Maven Central (not implementing now)

**Tasks** (documenting for future):
- [ ] Add Maven Central publishing to pom.xml
- [ ] Create maven-plugin module
- [ ] Sign artifacts with GPG
- [ ] Register namespace with Sonatype

---

## IMPLEMENTATION ORDER

### Sprint 1: Quick Wins (Moat)
1. **BPA Comparison Report** - High value, medium effort
2. **Dispatcher Config Analysis** - Medium value, fills gap
3. **Web UI Enhancements** - Visible differentiation

### Sprint 2: Deep Analysis (Moat)
4. **Enhanced HTL Analysis** - Technical depth
5. **Content Package Deep Scanning** - Completes coverage

### Sprint 3: Production Ready
6. **GHCR Publishing** - Easy adoption
7. **Documentation** - Professional appearance
8. **Sample Workflows** - Reduce friction

---

## SUCCESS METRICS

| Metric | Current | Target |
|--------|---------|--------|
| HTL patterns detected | 1 | 6+ |
| Cloud rules with CST ID | ~50% | 100% |
| Docker pulls | 0 | Tracked |
| UI features | Basic | Rich |
| README screenshots | 0 | 3+ |

---

## RISKS & MITIGATIONS

| Risk | Mitigation |
|------|------------|
| HTL parsing complexity | Start with regex, consider full parser later |
| Dispatcher format variations | Support common patterns first |
| BPA format changes | Version the mapping, note compatibility |
| Breaking existing tests | Run full test suite after each change |
