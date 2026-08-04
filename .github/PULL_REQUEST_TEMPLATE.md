## What does this change?

<!-- What does this PR do, and why? Link any related issue. -->

## How was this verified?

<!--
Describe how you confirmed this actually works, not just that it compiles.
For a new analyzer/rule: what real (or realistic) input did you run it against, and what did it correctly flag/not-flag?
For a bug fix: what was the concrete reproduction, and how did you confirm the fix resolves it?
-->

## Checklist

- [ ] `mvn verify` passes locally
- [ ] Tests added/updated for the change
- [ ] `CHANGELOG.md` updated under `[Unreleased]`
- [ ] If this changes a security-relevant code path (XML parsing, report generation, the REST/GraphQL API surface), I've considered whether it needs a new `spotbugs-security-exclude.xml` entry (with rationale) or whether the SpotBugs security gate should catch it as-is
- [ ] If this is an architectural decision with lasting implications (not just a bug fix), consider adding an ADR under `docs/adr/`
