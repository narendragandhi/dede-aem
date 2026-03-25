# Dede: AEM Cloud Readiness Analyzer
## Presentation for AEM Architects

---

## Slide 1: The Problem

### Current State of Cloud Migration Analysis

- **Multiple tools** with inconsistent rule sets (BPA, CAM, manual review)
- **Rules scattered** across Adobe documentation, release notes, SDK changelogs
- **Gap between source and package analysis** - issues slip through
- **No single source of truth** - teams maintain duplicate rule lists
- **Manual correlation** - no unified view of all findings

> "We fixed the BPA warnings but Cloud Manager still failed"

---

## Slide 2: The Solution

### Dede: Unified Analysis Platform

```
        ┌─────────────────────────┐
        │   forbidden-apis.json   │  ← ONE file to maintain
        │   (Single Source of     │
        │        Truth)           │
        └───────────┬─────────────┘
                    │
     ┌──────────────┼──────────────┐
     ▼              ▼              ▼
┌─────────┐   ┌──────────┐   ┌──────────┐
│ Static  │   │  OakPal  │   │  OakPal  │
│Analysis │   │  Paths   │   │  OSGi    │
└────┬────┘   └────┬─────┘   └────┬─────┘
     │             │              │
     └─────────────┼──────────────┘
                   ▼
            ┌────────────┐
            │   Graph    │  ← Unified findings
            │  Database  │     + impact analysis
            └────────────┘
```

---

## Slide 3: What Makes This Different

| Feature | Traditional Tools | Dede |
|---------|-------------------|------|
| Rule maintenance | Multiple configs | **One JSON file** |
| Source + Package analysis | Separate tools | **Unified** |
| Impact analysis | Manual | **Graph-based** |
| Custom rules | Code changes | **JSON config** |
| CI/CD ready | Manual export | **API/CLI native** |

---

## Slide 4: Two Layers of Protection

### Layer 1: Static Analysis (Before Build)

Scans `.java` source files:
- Forbidden API imports (`com.day.cq.*.impl`)
- Method calls (`loginAdministrative()`)
- Deprecated annotations (Felix SCR)
- Hardcoded paths in code

### Layer 2: OakPal Package Simulation (After Build)

Simulates package installation:
- Actual content paths (`/libs` modifications)
- OSGi config validation
- Filter.xml coverage
- ACL patterns
- Content deletions

> **Static analysis catches code issues. OakPal catches deployment issues.**

---

## Slide 5: The Catalog

### `forbidden-apis.json` - Complete Rule Set

```json
{
  "categories": [
    {
      "name": "ADMIN_SESSION",
      "severity": "CRITICAL",
      "methods": ["loginAdministrative"],
      "replacement": "Use service users"
    }
  ],
  "legacyPaths": [
    {
      "path": "/etc/designs",
      "migrationTarget": "/apps/<project>/clientlibs",
      "severity": "MAJOR"
    }
  ],
  "deprecatedOsgiPids": [...],
  "cloudManagedOsgiPids": [...]
}
```

**Current coverage:**
- 15 API categories
- 20 legacy paths with migration targets
- 5 deprecated OSGi PIDs
- 3 cloud-managed PIDs
- 4 deprecated annotation patterns

---

## Slide 6: Severity Mapping

### Clear Deployment Impact

| Severity | Meaning | Cloud Manager |
|----------|---------|---------------|
| **SEVERE / CRITICAL** | Forbidden | Pipeline FAILS |
| **MAJOR / HIGH** | Requires migration | Runtime issues |
| **MINOR / MEDIUM** | Deprecated | Works, should fix |

### Path Examples

| Path | Severity | Why |
|------|----------|-----|
| `/libs/*` | SEVERE | Immutable in cloud |
| `/etc/packages` | SEVERE | No package manager |
| `/etc/designs` | MAJOR | Migrate to `/apps` |
| `/etc/mobile` | MINOR | Deprecated feature |

---

## Slide 7: Graph-Based Impact Analysis

### Answer Questions Like:

- "What code depends on this deprecated API?"
- "If we migrate `/etc/designs`, what templates are affected?"
- "Show me all `loginAdministrative()` usages and their call chains"

```
Query: MATCH (v:VIOLATION)-[:FOUND_IN]->(c:CLASS)-[:DEPENDS_ON*]->(d)
       WHERE v.severity = 'CRITICAL'
       RETURN v, c, d
```

### Visualization

```
loginAdministrative() [CRITICAL]
    └── ContentService.java
        ├── PagePublisher.java
        │   └── ScheduledPublishJob.java
        └── AssetProcessor.java
            └── WorkflowStep.java
```

---

## Slide 8: Sample Output

### Report Summary

```
═══════════════════════════════════════════════
         CLOUD READINESS ANALYSIS REPORT
═══════════════════════════════════════════════

Overall Score: 62/100
Cloud Ready: NO

SEVERE/CRITICAL:  8  ████████░░░░░░░░░░░░
MAJOR/HIGH:      23  ███████████████████████
MINOR/MEDIUM:    16  ████████████████░░░░

Top Issues:
1. /libs modification (ui.apps) - SEVERE
2. loginAdministrative() x3 - CRITICAL
3. Cloud-managed OSGi PIDs x2 - SEVERE
4. /etc/designs content - MAJOR
5. Hardcoded secrets in OSGi - MAJOR
```

---

## Slide 9: OSGi Validation Deep Dive

### What We Check

1. **Deprecated PIDs**
   - Replication agents (cloud-managed)
   - WebDAV servlets (disabled)
   - Social login providers (deprecated)

2. **Cloud-Managed PIDs** (cannot customize)
   - `LoginAdminWhitelist`
   - `RepositoryInitializer`
   - `ServiceUserMapperImpl`

3. **Security Patterns**
   - Service user → admin mapping
   - Hardcoded secrets (should use `$[env:VAR]`)
   - Wildcard service mappings

4. **Best Practices**
   - Run mode specificity
   - Duplicate configurations

---

## Slide 10: Extending the Catalog

### Adding New Rules (No Code Changes)

Adobe releases new deprecation? Just edit JSON:

```json
{
  "path": "/etc/commerce",
  "migrationTarget": "/var/commerce",
  "severity": "MAJOR",
  "description": "Commerce paths moved in AEM 2024.3"
}
```

Both static analysis AND OakPal checks automatically use it.

### Project-Specific Rules

```json
{
  "name": "ACME_INTERNAL",
  "description": "Acme legacy code patterns",
  "severity": "HIGH",
  "packages": ["com.acme.legacy.*"],
  "replacement": "Use com.acme.modern.* packages"
}
```

---

## Slide 11: Integration Options

### CI/CD Pipeline

```yaml
# GitHub Actions / Azure DevOps
- name: Cloud Readiness Check
  run: |
    java -jar dede.jar analyze ./aem-project
    java -jar dede.jar report --format junit > results.xml

- name: Fail on Critical
  run: |
    java -jar dede.jar check --fail-on CRITICAL
```

### IDE Integration (Future)

- IntelliJ plugin for real-time warnings
- VS Code extension
- SonarQube custom rules export

---

## Slide 12: Comparison Matrix

| Capability | BPA | CAM | Repository Modernizer | **Dede** |
|------------|-----|-----|----------------------|----------|
| Source code analysis | | ✓ | | ✓ |
| Package simulation | | | | ✓ |
| OakPal integration | | | | ✓ |
| Single rule catalog | | | | ✓ |
| Graph visualization | | | | ✓ |
| Custom rules (JSON) | | | | ✓ |
| Impact analysis | | | | ✓ |
| CI/CD native | | | ✓ | ✓ |

---

## Slide 13: Architecture Review Request

### Questions for Architects

1. **Catalog completeness** - Are we missing any critical paths/APIs?
2. **Severity mapping** - Do severity levels align with your experience?
3. **Migration targets** - Are the suggested migration paths correct?
4. **Additional checks** - What other validations would be valuable?

### Artifacts for Review

- `forbidden-apis.json` - Full rule catalog
- `ARCHITECTURE.md` - Technical documentation
- `SAMPLE-REPORT.md` - Example output format

---

## Slide 14: Roadmap

### Current (v1.0)
- Static Java analysis
- OakPal package validation
- Graph integration
- CLI interface

### Planned (v1.1)
- HTL/Sightly analysis
- Content fragment validation
- Enhanced reporting (HTML/PDF)

### Future (v2.0)
- IDE plugins
- SonarQube integration
- Auto-fix suggestions
- Migration script generation

---

## Slide 15: Next Steps

1. **Review** - Examine `forbidden-apis.json` for completeness
2. **Pilot** - Run against one migration project
3. **Feedback** - Identify gaps and false positives
4. **Iterate** - Refine rules based on real findings
5. **Adopt** - Integrate into standard migration workflow

### Contact

- Repository: [link]
- Documentation: [link]
- Issues/Feedback: [link]

---

## Appendix: Technical Details

### Stack
- Java 17 / Spring Boot
- OakPal (Oak Package Validator)
- JavaParser (AST analysis)
- Neo4j / In-memory graph

### Key Files
```
src/main/resources/
└── forbidden-apis.json          # Rule catalog

src/main/java/com/dede/cloud/
├── ForbiddenApiCatalog.java     # Loads/queries rules
├── OakPalPackageValidator.java  # Orchestrates OakPal
├── CloudServicePathCheck.java   # Path validation
├── OsgiConfigCheck.java         # OSGi validation
└── AclSecurityCheck.java        # ACL validation
```

---

*Dede - Making AEM Cloud Migration Predictable*
