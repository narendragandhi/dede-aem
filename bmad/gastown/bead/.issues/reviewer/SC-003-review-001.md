---
id: SC-003-review-001
workflow_id: SC-003
type: review
agent: reviewer
status: pending
priority: high
created: 2026-04-08T00:00:00Z
updated: 2026-04-08T00:00:00Z
depends_on: [SC-003-impl-001, SC-003-test-001]
blocks: [SC-004-impl-001, SC-005-impl-001]
---

# Review: SupplyChainAnalyzer service

## Context

Review the new `com.dede.supply` package, graph enum additions, and known-publishers.json.

**Implementation**: bmad/gastown/bead/.issues/coder/SC-003-impl-001.md
**Tests**: bmad/gastown/bead/.issues/tester/SC-003-test-001.md
**Specification**: bmad/gastown/bead/.issues/docs/SC-003-spec-001.md

## Review Checklist

### Security
- [ ] XML parsing uses `DocumentBuilderFactory` with XXE disabled:
  `factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)`
- [ ] `Files.find()` walk does not follow symlinks (avoid path traversal in scanned projects)
- [ ] No user-supplied strings passed to shell commands (none expected — flag if present)

### Correctness
- [ ] SNAPSHOT check correctly handles null scope (default = compile, should flag)
- [ ] Typosquat check compares *prefix segments*, not full groupId (avoids `org.apache.sling.api` vs `org.apache.sling` distance of 4)
- [ ] UNVERIFIED_REPOSITORY check uses `startsWith` on normalized URL (strip trailing slash)
- [ ] EMBEDDED_JAR walk covers both `ui.apps` and `ui.content` paths

### Code Quality
- [ ] `parseDependencies(Path)` is package-private for SC-004 reuse — not leaked via public API
- [ ] `TyposquatDetector` loads `known-publishers.json` via `@PostConstruct` — not on every call
- [ ] `known-publishers.json` covers the 10 most common AEM/OSGi publishers at minimum

### Tests
- [ ] XXE fixture test present (pom.xml with DOCTYPE declaration should not cause SSRF)
- [ ] Distance-1 edge cases: empty string, single char, identical strings

## Progress Log

### 2026-04-08T00:00:00Z
Review initiated.

## Review Findings

### Critical (Must Fix)
<!-- to be filled — XXE protection is critical -->

### Major (Should Fix)
<!-- to be filled -->

### Minor (Consider)
<!-- to be filled -->

## Review Decision

- [ ] Approved — unblocks SC-004 and SC-005
- [ ] Approved with minor changes
- [ ] Request changes
