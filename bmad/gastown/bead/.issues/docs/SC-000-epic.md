---
id: SC-000
workflow_id: SC
type: epic
agent: mayor
status: in_progress
priority: high
created: 2026-04-08T00:00:00Z
updated: 2026-04-08T00:00:00Z
depends_on: []
blocks: []
---

# Epic: Supply Chain Security Integration (SC-000)

## Context

Extending dede-java with supply chain security capabilities inspired by
[elastic/supply-chain-monitor](https://github.com/elastic/supply-chain-monitor).

Dede currently detects AEM cloud violations and internal API misuse. This epic adds:
1. Detection of supply chain attack patterns in Java source (obfuscated exec, remote classloading, process spawning)
2. Maven dependency risk analysis (SNAPSHOT abuse, typosquatting, unverified repositories)
3. Dependency change tracking between graph snapshots

## Bead Map

| Bead | Title | Depends On | Status |
|------|-------|-----------|--------|
| SC-001 | SUPPLY_CHAIN categories in forbidden-apis.json | — | pending |
| SC-002 | ForbiddenApiScanner malicious code detection | SC-001 | pending |
| SC-003 | SupplyChainAnalyzer service | SC-001 | pending |
| SC-004 | DeltaAnalyzer Maven dependency tracking | SC-003 | pending |
| SC-005 | REST endpoint /api/analysis/supply-chain | SC-003, SC-004 | pending |

## Dependency Graph

```
SC-001 (JSON config — no Java changes)
  ├── SC-002 (scanner picks up new categories)
  └── SC-003 (analyzer uses same Severity enum)
        └── SC-004 (delta reuses pom parsing from SC-003)
              └── SC-005 (endpoint wires SC-003 + SC-004)
```

## Shared Context

### New Package
`com.dede.supply` — supply chain analysis services

### New Files
| File | Purpose |
|------|---------|
| `supply/SupplyChainAnalyzer.java` | Main service: scans all pom.xml, produces risk nodes |
| `supply/MavenDependency.java` | Record: groupId, artifactId, version, scope |
| `supply/SupplyChainRisk.java` | Record: riskType, severity, message, dependency |
| `supply/TyposquatDetector.java` | Edit-distance check against known-publishers.json |
| `resources/known-publishers.json` | Allowlist of legitimate Maven groupIds |

### Modified Files
| File | Change |
|------|--------|
| `resources/forbidden-apis.json` | 3 new SUPPLY_CHAIN_* categories |
| `cloud/ForbiddenApiScanner.java` | `scanForMaliciousPatterns()` method |
| `domain/DeltaAnalyzer.java` | `DependencySnapshot` + `DependencyDelta` inner classes |
| `api/AnalysisController.java` | `GET /api/analysis/supply-chain` endpoint |

### NodeType additions
Add `SUPPLY_CHAIN_RISK` to the `NodeType` enum.

### RelationshipType additions
Add `INTRODUCES_RISK` to the `RelationshipType` enum.

## Quality Gates

- [ ] Baseline 116 tests still pass after every bead
- [ ] New tests achieve >= 80% coverage on new classes
- [ ] `mvn compile` clean with no warnings on new code
- [ ] Swagger UI reflects new endpoint automatically
- [ ] CHANGELOG.md updated at SC-005 completion

## Progress Log

### 2026-04-08T00:00:00Z
Epic created by Mayor. Source: elastic/supply-chain-monitor analysis.
Beads SC-001 through SC-005 created and filed.
