# ADR 003: Centralized XXE Hardening via XmlSecurity

## Status
Accepted

## Context
Dede parses XML pulled from whatever project or content package it's pointed at, including third-party AEM packages under audit -- the README's own "acquisition due diligence, unfamiliar codebases" use case. Auditing found 8 `DocumentBuilder.parse()` call sites across the codebase; 4 already had hand-written XXE hardening (`disallow-doctype-decl`, external entities off, `setExpandEntityReferences(false)`), copy-pasted independently in `AclAnalyzer`, `ContentPackageScanner`, `JcrContentParser`, and `WorkflowModelParser`. The other 4 (`OsgiServiceParser` x2, `SlingClientLibParser`, `SlingResourceParser`) had no hardening at all -- a real, unmitigated CWE-611 vulnerability: a malicious package with an XXE payload in any parsed XML file could exfiltrate local files or cause denial of service when Dede scans it.

FindSecBugs' `XXE_DOCUMENT` detector flags all 8 regardless: it doesn't do cross-method taint tracking, so it can't verify that a factory hardened in a constructor is later used safely in a different method.

## Decision
One class, `com.dede.security.XmlSecurity`, with a single static method `newSafeDocumentBuilderFactory()` implementing the full hardening set (disallow-doctype-decl, external general/parameter entities off, XInclude off, `setExpandEntityReferences(false)`). All 8 call sites -- both the 4 that already had inline hardening and the 4 that had none -- now go through this one method. The 4 already-hardened classes were refactored onto it too, not left with their working-but-duplicated inline versions.

The now-verified-safe sites are individually excluded from the SpotBugs security gate by exact class name (see ADR 002), not by bug pattern -- so a future class that calls `DocumentBuilderFactory.newInstance()` directly instead of going through `XmlSecurity` still fails the build.

## Consequences
- **Positive**: One place to audit for XXE safety across the whole codebase; a future contributor adding a new XML parser has an obvious, discoverable utility to use instead of copy-pasting hardening (or forgetting it).
- **Positive**: Fixes a real, previously-unmitigated vulnerability in 4 of 8 sites, not just a lint finding.
- **Negative**: FindSecBugs will keep flagging all 8 sites regardless of the fix (a known tool limitation, not a real risk) -- reviewers need to know to check `XmlSecurity` usage directly rather than trusting the scanner's clean bill of health on this specific bug class.
