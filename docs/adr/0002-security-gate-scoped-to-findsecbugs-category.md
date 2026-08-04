# ADR 002: Security CI Gate Scoped to FindSecBugs' SECURITY Category

## Status
Accepted

## Context
`mvn spotbugs:check` was wired into CI with `|| true` and `continue-on-error: true`, so it could never fail the build regardless of findings -- pure theater. Removing the escape hatch and running vanilla SpotBugs against this codebase surfaces ~200 pre-existing findings, almost entirely `EI_EXPOSE_REP`/`EI_EXPOSE_REP2` (encapsulation) and `VA_FORMAT_STRING_USES_NEWLINE` (style) -- real but unrelated to security, and never triaged. Gating the build on all of them on day one would make CI permanently red on backlog, not on new problems.

Adding the FindSecBugs plugin surfaces genuinely security-relevant categories (XXE, ReDoS, path traversal, XML/HTML injection, log injection) that vanilla SpotBugs' `SECURITY` category otherwise misses almost entirely.

## Decision
Two-file filter setup, both referenced from `pom.xml`:
- `spotbugs-security-include.xml`: whitelist, scopes `spotbugs:check` to `Bug category="SECURITY"` only. This is a **security gate**, not a general code-quality gate.
- `spotbugs-security-exclude.xml`: within that category, two kinds of exclusion, kept explicit rather than silent:
  1. **By pattern** (`CRLF_INJECTION_LOGS`, `SPRING_ENDPOINT`, `IMPROPER_UNICODE`): high-volume, lower-severity findings deferred as backlog. A *new* instance of these won't be caught either -- an explicit scoping tradeoff.
  2. **By exact class** (`XXE_DOCUMENT`, `POTENTIAL_XML_INJECTION`, `PATH_TRAVERSAL_IN`): scoped only to classes that were individually audited and either fixed-and-verified-safe or manually confirmed as false positives (see ADR 003). A *new* class introducing an unhardened parser, an unescaped sink, or a web-reachable path traversal still fails the build.

Checkstyle was left with its original `|| true` -- it's a style linter, not a security gate, and out of scope for this pass.

## Consequences
- **Positive**: The gate is real (verified: a hand-introduced unhardened `DocumentBuilderFactory.newInstance()` in a new class would fail the build) without being immediately, uselessly red on ~200 unrelated pre-existing findings.
- **Positive**: Anyone adding a new suppression must add a `<Match>` with a rationale comment in `spotbugs-security-exclude.xml` -- suppressions are centralized and reviewable in one file, not scattered `@SuppressFBWarnings` annotations.
- **Negative**: The by-pattern exclusions are a real gap -- a genuinely new CRLF log injection or `SPRING_ENDPOINT` finding is silently allowed through today. Tracked as backlog, not fixed.
- **Negative**: `OWASP Dependency-Check` (the other half of the "make the gates real" work) additionally requires an `NVD_API_KEY` secret to run at all -- without one, NVD returns a hard 403/404 (confirmed by running it locally), not just a slow response. CI degrades to skip-with-warning rather than hard-failing when the key is absent.
