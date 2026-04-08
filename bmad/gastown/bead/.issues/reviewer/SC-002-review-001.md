---
id: SC-002-review-001
workflow_id: SC-002
type: review
agent: reviewer
status: pending
priority: high
created: 2026-04-08T00:00:00Z
updated: 2026-04-08T00:00:00Z
depends_on: [SC-002-impl-001, SC-002-test-001]
blocks: [SC-005-impl-001]
---

# Review: ForbiddenApiScanner malicious pattern detection

## Context

Review `scanForMaliciousPatterns()` implementation and fixture-based tests.

**Implementation**: bmad/gastown/bead/.issues/coder/SC-002-impl-001.md
**Tests**: bmad/gastown/bead/.issues/tester/SC-002-test-001.md
**Specification**: bmad/gastown/bead/.issues/docs/SC-002-spec-001.md

## Review Checklist

- [ ] AST visitor does not swallow parse exceptions silently (log + skip, same as `scanJavaFile`)
- [ ] `Files.walk()` is properly closed (try-with-resources)
- [ ] Annotation name check is by simple name only (e.g. `"Activate"`) — not FQN — consistent with existing scanner
- [ ] `MaliciousPatternViolation` record fields are all non-null-safe (primitives for line)
- [ ] Graph node creation follows existing `createVulnerabilityNode` pattern — no raw string keys
- [ ] Fixture files do not contain actual malicious payloads (use benign byte strings in tests)
- [ ] False positive analysis: `decode` method name — could clash with custom `decode()` methods; confirm the check also verifies the receiver is Base64-related
- [ ] Test coverage >= 80% on new code paths

## Progress Log

### 2026-04-08T00:00:00Z
Review initiated.

## Review Findings

### Critical (Must Fix)
<!-- to be filled -->

### Major (Should Fix)
<!-- to be filled -->

### Minor (Consider)
<!-- to be filled -->

## Review Decision

- [ ] Approved — unblocks SC-005
- [ ] Approved with minor changes
- [ ] Request changes
