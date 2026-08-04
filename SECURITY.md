# Security Policy

## Supported Versions

Dede-Java is pre-1.0 (`0.0.1-SNAPSHOT`) and does not yet maintain parallel supported branches. Security fixes land on `main` and are backported only if a tagged release exists that needs them.

## Reporting a Vulnerability

**Do not open a public GitHub issue for a security vulnerability.**

Instead, use [GitHub Security Advisories](https://github.com/narendragandhi/dede-aem/security/advisories/new) to report privately. Include:

- A description of the vulnerability and its potential impact
- Steps to reproduce (a minimal test case is ideal)
- The affected version/commit

You should expect an initial response within 5 business days. This is a small, solo-maintained project -- please be patient, but a private report will always be prioritized over waiting for a public issue to be noticed.

## Scope

Dede-Java parses and analyzes source code and content packages that may originate from third parties under audit (see the README's "acquisition due diligence, unfamiliar codebases" use case). In scope:

- Vulnerabilities in how Dede parses untrusted input (XXE, path traversal, injection into generated reports, denial of service via malformed input, etc.)
- Authentication/authorization gaps in the REST/GraphQL API when run as a service
- Supply-chain issues in the published Docker image or release artifacts

Findings from Dede's own static analysis of *your* codebase are not a Dede vulnerability -- report those upstream to the relevant project, or file a normal (non-security) issue here if Dede's detection itself is wrong (false positive/negative).

## Disclosure

Given the project's current size, there is no formal embargo/disclosure timeline yet. Confirmed vulnerabilities will be fixed on `main` and credited in `CHANGELOG.md` unless the reporter requests otherwise.
