# Security Audit & Reachability Analysis

## 1. The Problem with Standard Scanners
Tools like OWASP Dependency-Check or Snyk provide a "Flat List" of vulnerable JARs. In a project with 1,000+ dependencies, architects are overwhelmed with "False Positives" (vulnerabilities in code that is never actually executed or reachable).

## 2. The Dede-Java Solution: Reachability
Dede-Java uses its **Topological Graph** to perform **Reachability Analysis**. 

*   **Detection**: It identifies the vulnerable library (e.g., `commons-collections:3.1`).
*   **Traceability**: It traces the dependency chain: `Vulnerability` ➡️ `Bundle` ➡️ `OSGi Service` ➡️ `Sling Model` ➡️ `HTL Component`.
*   **Prioritization**: It ranks vulnerabilities based on their **Blast Radius**. A vulnerability that is reachable from 50 HTL components (public UI) is prioritized over one that is only reachable from an internal maintenance task.

## 3. How to Run
Execute the security audit via the CLI:
```bash
java -jar dede.jar <path-to-project> --security
```

This alone only reaches vulnerabilities dede's own scanners create (forbidden-API usage, dispatcher misconfigurations, ...). To rank real CVEs by blast radius, feed it an OWASP Dependency-Check JSON report:

```bash
mvn org.owasp:dependency-check-maven:check -Dformat=JSON
java -jar dede.jar <path-to-project> --security --dependency-check-report target/dependency-check-report.json
```

Dede doesn't re-implement CVE detection or talk to the NVD directly; it consumes Dependency-Check's own findings and re-ranks them by real reachability instead of flat CVSS score.

## 4. Feature Roadmap
*   **NVD API Integration**: ~~Live feed from the National Vulnerability Database~~ Superseded: dede consumes an OWASP Dependency-Check JSON report instead of talking to the NVD directly (see "How to Run" above) -- avoids re-implementing what Dependency-Check already does well.
*   **Retire.js Mapping**: Deep security analysis for AEM ClientLibs (JS/CSS).
*   **Exploit Path Visualization**: Visual highlighting of the "Attack Vector" in the Graph UI.
