# Dede: AEM Cloud Service Readiness Analyzer

## Executive Summary

Dede is a unified code analysis platform that validates AEM projects for Cloud Service compatibility. It combines **static source analysis** with **OakPal package simulation** against a **single source of truth** for forbidden APIs, deprecated patterns, and path restrictions.

## Why This Matters

| Challenge | Traditional Approach | Dede Approach |
|-----------|---------------------|---------------|
| Multiple tools with different rule sets | BPA, CAM, manual review | Single catalog, multiple analyzers |
| Rules scattered across documentation | Manual tracking | `forbidden-apis.json` - one file |
| Source vs. package analysis gaps | Separate tools | Unified graph of all findings |
| Maintenance burden | Update multiple configs | Update one JSON file |

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                        forbidden-apis.json                           │
│            SINGLE SOURCE OF TRUTH FOR ALL VALIDATION RULES           │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌─────────────────┐ │
│  │ 15 API      │ │ 20 Legacy   │ │ 5 Deprecated│ │ 3 Cloud-Managed │ │
│  │ Categories  │ │ Paths       │ │ OSGi PIDs   │ │ OSGi PIDs       │ │
│  └─────────────┘ └─────────────┘ └─────────────┘ └─────────────────┘ │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
        ┌──────────────────────┼──────────────────────┐
        │                      │                      │
        ▼                      ▼                      ▼
┌───────────────┐      ┌───────────────┐      ┌───────────────┐
│    STATIC     │      │    OAKPAL     │      │    OAKPAL     │
│   ANALYSIS    │      │  PATH CHECK   │      │  OSGI CHECK   │
├───────────────┤      ├───────────────┤      ├───────────────┤
│ Scans .java   │      │ Simulates pkg │      │ Validates     │
│ source files  │      │ installation  │      │ OSGi configs  │
│               │      │               │      │               │
│ Detects:      │      │ Detects:      │      │ Detects:      │
│ • API imports │      │ • /libs mods  │      │ • Deprecated  │
│ • Method calls│      │ • /etc paths  │      │   PIDs        │
│ • Annotations │      │ • Deletions   │      │ • Cloud-      │
│ • Patterns    │      │ • Mutable in  │      │   managed     │
│               │      │   immutable   │      │ • Hardcoded   │
│               │      │               │      │   secrets     │
└───────┬───────┘      └───────┬───────┘      └───────┬───────┘
        │                      │                      │
        └──────────────────────┼──────────────────────┘
                               │
                               ▼
                    ┌─────────────────────┐
                    │    GRAPH DATABASE   │
                    │   (Neo4j/In-Memory) │
                    ├─────────────────────┤
                    │ Unified view:       │
                    │ • Code structure    │
                    │ • Dependencies      │
                    │ • All violations    │
                    │ • Impact analysis   │
                    └─────────────────────┘
```

## Validation Layers

### Layer 1: Static Source Analysis

**What it does:** Scans Java source files before compilation

**Components:**
- `ForbiddenApiAnalyzer` - Detects forbidden class/method usage
- `LegacyPathDetector` - Finds hardcoded legacy paths in source
- `DeprecatedAnnotationChecker` - Identifies deprecated OSGi annotations

**Catches:**
- `loginAdministrative()` calls
- `com.day.cq.*.impl` package imports
- Felix SCR annotations instead of OSGi R7
- Hardcoded `/etc/designs` paths in Java

### Layer 2: OakPal Package Simulation

**What it does:** Simulates content package installation in a temporary Oak repository

**Components:**
- `CloudServicePathCheck` - Validates paths against forbidden list
- `OsgiConfigCheck` - Validates OSGi configurations
- `AclSecurityCheck` - Checks ACL patterns

**Catches (that static analysis cannot):**
- Content actually deployed to `/libs`
- Filter.xml covering forbidden paths
- OSGi configs with hardcoded secrets
- Service user mappings to admin
- Package deletions of production content

### Layer 3: Graph Integration

**What it does:** Creates a queryable knowledge graph of all findings

**Benefits:**
- Impact analysis: "What code depends on this deprecated API?"
- Migration planning: "Show all /etc paths and their dependencies"
- Progress tracking: "How many violations fixed this sprint?"

## Rule Catalog Structure

The `forbidden-apis.json` file is the single source of truth:

```json
{
  "version": "1.1",
  "categories": [
    {
      "name": "ADMIN_SESSION",
      "description": "Administrative session methods that bypass ACLs",
      "severity": "CRITICAL",
      "methods": ["loginAdministrative", "getAdministrativeResourceResolver"],
      "replacement": "Use service users with ServiceUserMapped interface"
    }
  ],
  "legacyPaths": [
    {
      "path": "/etc/packages",
      "migrationTarget": null,
      "severity": "SEVERE",
      "description": "Package installation via /etc/packages is forbidden"
    },
    {
      "path": "/etc/designs",
      "migrationTarget": "/apps/<project>/clientlibs",
      "severity": "MAJOR",
      "description": "Design pages are deprecated - use clientlibs in /apps"
    }
  ],
  "deprecatedOsgiPids": [...],
  "cloudManagedOsgiPids": [...]
}
```

## Severity Levels

| Level | Meaning | Deployment Impact |
|-------|---------|-------------------|
| **SEVERE/CRITICAL** | Forbidden in Cloud Service | Will fail Cloud Manager pipeline |
| **MAJOR/HIGH** | Requires migration | May cause runtime issues |
| **MINOR/MEDIUM** | Deprecated | Should migrate, still works |

## Path Coverage

### SEVERE (Deployment Blockers)
| Path | Reason |
|------|--------|
| `/etc/packages` | Package installation forbidden |
| `/etc/replication` | Replication agents cloud-managed |
| `/etc/map` | Sling mappings cloud-managed |
| `/var/classes` | Compiled JSPs cloud-managed |
| `/var/clientlibs` | Clientlib cache cloud-managed |
| `/libs` | Modification forbidden |

### MAJOR (Requires Migration)
| Path | Migration Target |
|------|------------------|
| `/etc/workflow/models` | `/var/workflow/models` |
| `/etc/designs` | `/apps/<project>/clientlibs` |
| `/etc/clientlibs` | `/apps/<project>/clientlibs` |
| `/etc/tags` | `/content/cq:tags` |
| `/etc/cloudservices` | `/conf/<project>/settings/cloudconfigs` |
| `/etc/dam` | `/conf/<project>/settings/dam` |

## API Categories

| Category | Severity | Example | Replacement |
|----------|----------|---------|-------------|
| INTERNAL_API | CRITICAL | `com.day.cq.*.impl` | Public API interfaces |
| ADMIN_SESSION | CRITICAL | `loginAdministrative()` | Service users |
| DEPRECATED_REPLICATION | HIGH | `Replicator` | Sling Content Distribution |
| DEPRECATED_WORKFLOW | HIGH | `WorkflowSession` | Sling Jobs |
| DEPRECATED_DAM | HIGH | `AssetManager.createAsset()` | Asset Compute |
| LOCAL_BINARY_STORAGE | CRITICAL | `getDataStore()` | Standard JCR Binary API |

## OSGi Configuration Validation

### Deprecated PIDs
- `com.day.cq.replication.impl.ReplicationAgentImpl`
- `org.apache.sling.jcr.davex.impl.servlets.SlingDavExServlet`
- `org.apache.sling.jcr.webdav.impl.servlets.SimpleWebDavServlet`

### Cloud-Managed PIDs (Cannot Customize)
- `org.apache.sling.jcr.base.internal.LoginAdminWhitelist`
- `org.apache.sling.jcr.repoinit.RepositoryInitializer`
- `org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl`

### Additional Checks
- Service user mappings to `admin` user
- Hardcoded secrets (should use `$[env:VAR_NAME]`)
- Missing run mode specificity
- Duplicate/conflicting configurations

## Comparison with Other Tools

| Feature | BPA | CAM | Dede |
|---------|-----|-----|------|
| Source code analysis | No | Yes | Yes |
| Package simulation | No | No | Yes (OakPal) |
| Single rule catalog | No | No | Yes |
| Graph-based impact analysis | No | No | Yes |
| Custom rule extension | Limited | Limited | Yes (JSON-based) |
| CI/CD integration | Manual | Manual | Yes (CLI/API) |

## Getting Started

### Run Analysis
```bash
./gradlew bootRun --args="analyze /path/to/aem-project"
```

### Validate Packages Only
```bash
./gradlew bootRun --args="validate-packages /path/to/aem-project"
```

### Generate Report
```bash
./gradlew bootRun --args="report /path/to/aem-project --format html"
```

## Extending the Catalog

To add a new forbidden pattern:

1. Edit `src/main/resources/forbidden-apis.json`
2. Add to appropriate section (`categories`, `legacyPaths`, etc.)
3. No code changes required - both static and OakPal checks will use it

Example - adding a new forbidden path:
```json
{
  "path": "/etc/newpath",
  "migrationTarget": "/conf/newpath",
  "severity": "MAJOR",
  "description": "New path deprecated in AEM 2024.1"
}
```

## Key Source Files

```
src/main/resources/
└── forbidden-apis.json              # Rule catalog (SINGLE SOURCE OF TRUTH)

src/main/java/com/dede/cloud/
├── ForbiddenApiCatalog.java         # Loads and queries rules
├── OakPalPackageValidator.java      # Orchestrates OakPal checks
├── CloudServicePathCheck.java       # OakPal: Path validation
├── OsgiConfigCheck.java             # OakPal: OSGi config validation
├── AclSecurityCheck.java            # OakPal: ACL validation
└── CloudReadinessReport.java        # Report generation
```

## References

- [AEM as a Cloud Service Migration Guide](https://experienceleague.adobe.com/docs/experience-manager-cloud-service/content/migration-journey/refactoring-tools/overview.html)
- [Content Package Structure](https://experienceleague.adobe.com/docs/experience-manager-cloud-service/content/implementing/developing/aem-project-content-package-structure.html)
- [OSGi Configuration](https://experienceleague.adobe.com/docs/experience-manager-cloud-service/content/implementing/deploying/configuring-osgi.html)
- [OakPal Documentation](https://adamcin.github.io/oakpal/)
