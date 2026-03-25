# Dede-Java Implementation Plan

## Bead-Based Task Breakdown

Each **bead** represents a discrete, testable unit of work. Beads are organized into **strands** (feature groups) that can be worked on in parallel or sequence.

---

## Strand 1: OSGi Configuration Parsing (Priority: HIGH)

### Bead 1.1: OSGi Config Parser Foundation
- **Goal:** Parse `.cfg.json` and `.config` files from OSGi configurations
- **Files to create:**
  - `src/main/java/com/dede/discovery/OsgiConfigParser.java`
- **Inputs:** `/apps/**/config*/` directories
- **Outputs:** `OSGI_CONFIG` and `OSGI_CONFIG_FACTORY` nodes
- **Test:** `OsgiConfigParserTest.java`
- **Acceptance:** Extracts PID, factory PID, and all properties

### Bead 1.2: Configuration-to-Service Linking
- **Goal:** Link configurations to the services they configure
- **Files to modify:**
  - `src/main/java/com/dede/discovery/OsgiLinker.java`
- **Relationships:** `CONFIG_BY`, `CONFIGURES`
- **Test:** Verify config → service edges created
- **Acceptance:** Config nodes connect to their target service components

### Bead 1.3: Environment-Specific Config Validation
- **Goal:** Detect config folder patterns (config.prod, config.author, etc.)
- **Files to modify:**
  - `src/main/java/com/dede/discovery/OsgiConfigParser.java`
- **Properties:** Add `runMode` property to config nodes
- **Test:** Parse configs with different run modes
- **Acceptance:** Run modes extracted and validated

### Bead 1.4: Service User Detection
- **Goal:** Identify service user mappings and credential configs
- **Files to create:**
  - `src/main/java/com/dede/security/ServiceUserAnalyzer.java`
- **Patterns:** `org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended-*`
- **Test:** Detect service user configurations
- **Acceptance:** Service users identified with their mapped bundles

---

## Strand 2: Advanced OSGi Service Resolution (Priority: HIGH)

### Bead 2.1: LDAP Target Filter Parser
- **Goal:** Parse `@Reference(target="...")` LDAP filter expressions
- **Files to create:**
  - `src/main/java/com/dede/osgi/LdapFilterParser.java`
- **Syntax:** Parse `(property=value)`, `(&...)`, `(|...)`, `(!...)`
- **Test:** `LdapFilterParserTest.java` with various filter patterns
- **Acceptance:** Filters parsed into queryable predicates

### Bead 2.2: Service Ranking Resolution
- **Goal:** Resolve service selection based on `service.ranking`
- **Files to modify:**
  - `src/main/java/com/dede/discovery/OsgiLinker.java`
- **Logic:** Higher ranking wins, then lower service.id
- **Test:** Multiple implementations, verify correct selection
- **Acceptance:** Correct service selected based on ranking

### Bead 2.3: Reference Cardinality Validation
- **Goal:** Validate `0..1`, `1..1`, `0..n`, `1..n` cardinalities
- **Files to create:**
  - `src/main/java/com/dede/intelligence/ReferenceValidator.java`
- **Detection:** Unsatisfied mandatory references
- **Test:** Detect missing mandatory services
- **Acceptance:** Report unsatisfied references with cardinality info

### Bead 2.4: Service Lifecycle State Tracking
- **Goal:** Track component activation states
- **Files to create:**
  - `src/main/java/com/dede/osgi/ComponentStateTracker.java`
- **States:** SATISFIED, UNSATISFIED, ACTIVE, REGISTERED
- **Test:** Simulate component states
- **Acceptance:** State info available for each component

---

## Strand 3: Cloud Readiness & Migration (Priority: HIGH)

### Bead 3.1: Forbidden API Catalog
- **Goal:** Comprehensive list of Cloud Service forbidden APIs
- **Files to create:**
  - `src/main/java/com/dede/cloud/ForbiddenApiCatalog.java`
  - `src/main/resources/forbidden-apis.json`
- **Categories:**
  - `com.day.cq.*.impl` (internal APIs)
  - `com.day.cq.replication` (deprecated)
  - `com.day.cq.workflow` (use Sling Jobs)
  - `com.day.cq.search` (use QueryBuilder)
- **Test:** `ForbiddenApiCatalogTest.java`
- **Acceptance:** Complete catalog with replacement recommendations

### Bead 3.2: Forbidden API Scanner
- **Goal:** Detect usage of forbidden APIs in source code
- **Files to create:**
  - `src/main/java/com/dede/cloud/ForbiddenApiScanner.java`
- **Integration:** Hook into `SourceParser` import analysis
- **Output:** VULNERABILITY nodes with severity levels
- **Test:** Detect forbidden imports and method calls
- **Acceptance:** All forbidden API usages flagged

### Bead 3.3: Legacy Path Detection
- **Goal:** Find legacy JCR paths incompatible with Cloud Service
- **Files to create:**
  - `src/main/java/com/dede/cloud/LegacyPathDetector.java`
- **Paths:**
  - `/etc/workflow/models` → `/var/workflow/models`
  - `/etc/designs` → `/apps/.../clientlibs`
  - `/etc/clientlibs` → `/apps/.../clientlibs`
  - `/etc/packages` (forbidden)
- **Test:** Scan JCR content for legacy paths
- **Acceptance:** Legacy paths identified with migration targets

### Bead 3.4: Administrative Session Detection
- **Goal:** Find `loginAdministrative()` and other deprecated auth
- **Files to modify:**
  - `src/main/java/com/dede/cloud/ForbiddenApiScanner.java`
- **Patterns:**
  - `resourceResolverFactory.getAdministrativeResourceResolver()`
  - `slingRepository.loginAdministrative()`
- **Replacement:** Service users
- **Test:** Detect admin session usage
- **Acceptance:** All admin session usages flagged with fix suggestions

### Bead 3.5: Cloud Readiness Report Generator
- **Goal:** Generate comprehensive cloud migration report
- **Files to create:**
  - `src/main/java/com/dede/cloud/CloudReadinessReport.java`
- **Sections:**
  - Forbidden APIs summary
  - Legacy paths summary
  - Service user requirements
  - Estimated migration effort
- **Output:** JSON and Markdown formats
- **Test:** Generate report for sample project
- **Acceptance:** Complete, actionable report generated

### Bead 3.6: OakPal Content Package Validation
- **Goal:** Validate content packages using OakPal simulation
- **Files to create:**
  - `src/main/java/com/dede/cloud/OakPalPackageValidator.java`
  - `src/main/java/com/dede/cloud/CloudServicePathCheck.java`
  - `src/main/java/com/dede/cloud/AclSecurityCheck.java`
  - `src/main/java/com/dede/cloud/OsgiConfigCheck.java`
  - `src/test/java/com/dede/cloud/OakPalPackageValidatorTest.java`
- **Dependencies:** `net.adamcin.oakpal:oakpal-core:2.3.0`
- **Capabilities:**
  - Simulates package installation in temporary Oak repository
  - Validates NodeType constraints
  - Detects forbidden Cloud Service paths
  - ACL security analysis
  - OSGi config validation (deprecated PIDs, sensitive values)
- **Output:** CONTENT_PACKAGE and PACKAGE_VIOLATION nodes in graph
- **Integration:** Results feed into CloudReadinessReport
- **Test:** Validate test packages with various violations
- **Acceptance:** All package-level Cloud Service violations detected

---

## Strand 4: Symbol Resolution & Type Safety (Priority: MEDIUM)

### Bead 4.1: JavaSymbolSolver Integration
- **Goal:** Integrate JavaParser's symbol solver for type resolution
- **Files to modify:**
  - `src/main/java/com/dede/discovery/SourceParser.java`
- **Setup:** Configure TypeSolver with JAR and source paths
- **Test:** Resolve method call target types
- **Acceptance:** Accurate type resolution across files

### Bead 4.2: Cross-JAR Type Resolution
- **Goal:** Resolve types from dependency JARs
- **Files to create:**
  - `src/main/java/com/dede/discovery/JarTypeSolver.java`
- **Integration:** Add JAR type solvers to symbol solver chain
- **Test:** Resolve types from external dependencies
- **Acceptance:** External types resolved correctly

### Bead 4.3: Method Call Graph Construction
- **Goal:** Build accurate method-to-method call graph
- **Files to modify:**
  - `src/main/java/com/dede/discovery/SourceParser.java`
- **Edges:** `CALLS` relationships with resolved target methods
- **Test:** Verify method call chains
- **Acceptance:** Complete call graph with qualified signatures

### Bead 4.4: Inherited Reference Detection
- **Goal:** Detect `@Reference` fields from parent classes/interfaces
- **Files to modify:**
  - `src/main/java/com/dede/discovery/SourceParser.java`
- **Logic:** Walk inheritance hierarchy for annotations
- **Test:** Detect inherited injected fields
- **Acceptance:** All inherited references captured

---

## Strand 5: Workflow & Asset Analysis (Priority: MEDIUM)

### Bead 5.1: Workflow Model Parser
- **Goal:** Parse AEM workflow model definitions
- **Files to create:**
  - `src/main/java/com/dede/discovery/WorkflowModelParser.java`
- **Paths:** `/var/workflow/models/**/*.xml`, `/conf/**/settings/workflow/models`
- **Output:** `WORKFLOW_PROCESS` nodes
- **Test:** Parse DAM Asset workflow
- **Acceptance:** Workflow steps and transitions extracted

### Bead 5.2: Workflow Process Step Linking
- **Goal:** Link workflow steps to implementing Java classes
- **Files to modify:**
  - `src/main/java/com/dede/discovery/WorkflowModelParser.java`
- **Pattern:** `process` property → Java class
- **Relationships:** `IMPLEMENTS` edges
- **Test:** Verify step-to-class linking
- **Acceptance:** Process steps linked to implementations

### Bead 5.3: Asset Microservices Detection
- **Goal:** Detect direct rendition access patterns
- **Files to create:**
  - `src/main/java/com/dede/cloud/AssetMicroservicesChecker.java`
- **Patterns:**
  - Direct `/jcr:content/renditions` access
  - `AssetManager` deprecated APIs
- **Test:** Detect forbidden rendition access
- **Acceptance:** Asset anti-patterns identified

### Bead 5.4: Sling Jobs Analysis
- **Goal:** Analyze Sling Job definitions and consumers
- **Files to create:**
  - `src/main/java/com/dede/discovery/SlingJobParser.java`
- **Annotations:** `@Component(property = "job.topics=...")`
- **Output:** `SLING_JOB` nodes with topic info
- **Test:** Detect job producers and consumers
- **Acceptance:** Job topology mapped

---

## Strand 6: Enhanced Security Analysis (Priority: MEDIUM)

### Bead 6.1: Servlet Security Audit
- **Goal:** Audit servlet exposure and authentication requirements
- **Files to create:**
  - `src/main/java/com/dede/security/ServletSecurityAuditor.java`
- **Checks:**
  - Public vs authenticated paths
  - Missing CSRF protection
  - Dangerous HTTP methods (DELETE, PUT without auth)
- **Test:** Audit sample servlets
- **Acceptance:** Security findings with severity levels

### Bead 6.2: XSS Pattern Detection
- **Goal:** Detect potential XSS vulnerabilities in HTL
- **Files to create:**
  - `src/main/java/com/dede/security/XssDetector.java`
- **Patterns:**
  - `@ context='unsafe'`
  - `@ context='html'` with user input
  - Missing display context
- **Test:** Detect XSS patterns in HTL files
- **Acceptance:** XSS risks identified with line numbers

### Bead 6.3: Access Control Analysis
- **Goal:** Analyze ACL definitions and permissions
- **Files to create:**
  - `src/main/java/com/dede/security/AclAnalyzer.java`
- **Patterns:** `rep:policy` nodes in content
- **Findings:** Overly permissive ACLs
- **Test:** Parse and analyze ACLs
- **Acceptance:** Permission risks identified

### Bead 6.4: Security Report Aggregator
- **Goal:** Combine all security findings into unified report
- **Files to create:**
  - `src/main/java/com/dede/security/SecurityReport.java`
- **Sections:**
  - Vulnerabilities by severity
  - Attack surface summary
  - Remediation recommendations
- **Output:** JSON, Markdown, SARIF formats
- **Test:** Generate comprehensive report
- **Acceptance:** Actionable security report

---

## Strand 7: AI Agent & Reasoning (Priority: LOW)

### Bead 7.1: LangGraph4j Foundation
- **Goal:** Set up LangGraph4j state graph infrastructure
- **Files to create:**
  - `src/main/java/com/dede/agent/GraphAgent.java`
  - `src/main/java/com/dede/agent/AgentState.java`
- **Dependencies:** Add LangGraph4j to pom.xml
- **Test:** Basic state graph execution
- **Acceptance:** Agent framework operational

### Bead 7.2: Input Parser Node
- **Goal:** Parse natural language queries into structured intents
- **Files to create:**
  - `src/main/java/com/dede/agent/nodes/InputParserNode.java`
- **Intents:** IMPACT_ANALYSIS, CYCLE_DETECTION, EXPLAIN_WIRING, etc.
- **Test:** Parse various query types
- **Acceptance:** Queries correctly classified

### Bead 7.3: Graph Tool Node
- **Goal:** Execute graph queries based on parsed intent
- **Files to create:**
  - `src/main/java/com/dede/agent/nodes/GraphToolNode.java`
- **Integration:** Call existing `GraphAgentSkills` methods
- **Test:** Execute various graph operations
- **Acceptance:** Correct results returned

### Bead 7.4: Answer Synthesizer Node
- **Goal:** Format query results into human-readable answers
- **Files to create:**
  - `src/main/java/com/dede/agent/nodes/AnswerSynthesizerNode.java`
- **Output:** Markdown-formatted explanations
- **Test:** Synthesize various result types
- **Acceptance:** Clear, actionable answers

### Bead 7.5: Multi-Step Reasoning Loop
- **Goal:** Enable iterative query refinement
- **Files to modify:**
  - `src/main/java/com/dede/agent/GraphAgent.java`
- **Logic:** Loop until answer confidence threshold met
- **Test:** Complex multi-step queries
- **Acceptance:** Accurate multi-hop reasoning

---

## Strand 8: Testing & Quality (Priority: HIGH)

### Bead 8.1: Integration Test Framework
- **Goal:** Set up integration testing infrastructure
- **Files to create:**
  - `src/test/java/com/dede/integration/IntegrationTestBase.java`
  - `src/test/resources/test-projects/` (sample AEM projects)
- **Framework:** Spring Boot Test + sample project fixtures
- **Acceptance:** Integration tests runnable

### Bead 8.2: Parser Integration Tests
- **Goal:** End-to-end tests for all parsers
- **Files to create:**
  - `src/test/java/com/dede/integration/ParserIntegrationTest.java`
- **Coverage:** All parser types with realistic inputs
- **Acceptance:** >80% parser code coverage

### Bead 8.3: API Integration Tests
- **Goal:** REST API end-to-end tests
- **Files to create:**
  - `src/test/java/com/dede/integration/ApiIntegrationTest.java`
- **Coverage:** All endpoints with various inputs
- **Acceptance:** All API contracts verified

### Bead 8.4: Performance Benchmarks
- **Goal:** Establish performance baselines
- **Files to create:**
  - `src/test/java/com/dede/benchmark/ScanBenchmark.java`
- **Metrics:** Scan time, memory usage, node/edge counts
- **Acceptance:** Benchmarks documented

---

## Strand 9: Visualization & UI (Priority: LOW)

### Bead 9.1: Static HTML Dashboard
- **Goal:** Basic HTML/JS dashboard for graph visualization
- **Files to create:**
  - `src/main/resources/static/index.html`
  - `src/main/resources/static/js/graph.js`
  - `src/main/resources/static/css/styles.css`
- **Library:** D3.js force-directed graph
- **Test:** Manual visual verification
- **Acceptance:** Interactive graph visible in browser

### Bead 9.2: Node Detail Panel
- **Goal:** Click-to-view node details
- **Files to modify:**
  - `src/main/resources/static/js/graph.js`
- **Display:** Properties, relationships, impact preview
- **Acceptance:** Node details shown on click

### Bead 9.3: Search & Filter
- **Goal:** Search nodes by name, type, or property
- **Files to modify:**
  - `src/main/resources/static/js/graph.js`
- **Features:** Typeahead search, type filters
- **Acceptance:** Nodes filterable and searchable

### Bead 9.4: Blast Radius Visualization
- **Goal:** Highlight impact when node selected
- **Files to modify:**
  - `src/main/resources/static/js/graph.js`
- **Display:** Color-coded direct/transitive dependencies
- **Acceptance:** Impact visually highlighted

---

## Implementation Priority Matrix

| Strand | Priority | Effort | Value | Dependencies |
|--------|----------|--------|-------|--------------|
| 1. OSGi Config | HIGH | Medium | High | None |
| 2. Service Resolution | HIGH | High | High | Strand 1 |
| 3. Cloud Readiness | HIGH | Medium | Very High | None |
| 8. Testing | HIGH | Medium | High | Strands 1-3 |
| 4. Symbol Resolution | MEDIUM | High | Medium | None |
| 5. Workflow Analysis | MEDIUM | Medium | Medium | Strand 4 |
| 6. Security Analysis | MEDIUM | Medium | High | Strands 1-3 |
| 7. AI Agent | LOW | Very High | Medium | All |
| 9. Visualization | LOW | Medium | Medium | None |

---

## Recommended Implementation Order

### Phase 1: Foundation (Beads 1.1-1.4, 3.1-3.5, 8.1)
- OSGi configuration parsing
- Cloud readiness scanning
- Integration test framework

### Phase 2: Resolution (Beads 2.1-2.4, 4.1-4.4)
- Advanced service resolution
- Symbol solver integration

### Phase 3: Security (Beads 6.1-6.4, 8.2-8.3)
- Security analysis features
- Comprehensive testing

### Phase 4: Extended Analysis (Beads 5.1-5.4)
- Workflow and asset analysis

### Phase 5: Intelligence (Beads 7.1-7.5)
- AI agent capabilities

### Phase 6: Polish (Beads 9.1-9.4, 8.4)
- Visualization dashboard
- Performance optimization

---

## Success Metrics

- **Code Coverage:** >80% for core packages
- **Scan Performance:** <30s for 1000-class project
- **API Response Time:** <100ms for node queries
- **Cloud Readiness:** 100% forbidden API detection
- **Security:** OWASP Top 10 coverage

