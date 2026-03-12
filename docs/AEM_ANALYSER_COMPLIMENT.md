# Dede-Java vs. AEM Analyser: The Architectural Intelligence Report

## 1. Executive Positioning
**AEM Analyser** (Adobe) is a **Compliance Validator**. It ensures your code adheres to the "Laws of the Cloud" (deprecated APIs, missing imports, restricted JCR paths).

**Dede-Java** is a **Topological Intelligence Engine**. It maps the **Connectivity and Impact** of your entire stack—from the UI (HTL/ClientLibs) to the deep backend (OSGi Services/Sling Models).

---

## 2. Feature Comparison Matrix

| Capability | AEM Analyser (Adobe) | Dede-Java |
| :--- | :--- | :--- |
| **Primary Goal** | Cloud Service Compliance | Architectural Governance & Impact Analysis |
| **Data Model** | Flat List of Violations | **Directed Multigraph** |
| **Full-Stack Traceability** | No (Isolated checks) | **Yes** (HTL ➡️ Sling Model ➡️ OSGi Service) |
| **Impact Analysis** | No | **Yes** (Transitive Blast Radius Mapping) |
| **Custom Rules** | No (Proprietary) | **Yes** (JSON-based `dede-rules.json`) |
| **UI awareness** | Low (JCR focus) | **High** (ClientLib & HTL Parsing) |
| **Agent-Ready** | No | **Yes** (Built-in GraphAgentSkills) |

---

## 3. Synergistic Integration: How Dede-Java Complements AEM Analyser

Dede-Java doesn't replace AEM Analyser; it **contextualizes** it. Here is how they work together:

### A. The "Impact Visualizer" for AEM Analyser Errors
AEM Analyser often reports: *"Error: Package 'com.day.cq.wcm.api' is deprecated."*
*   **The Problem**: The developer sees 500 instances of this error and doesn't know where to start.
*   **The Dede-Java Solution**: Ingest the AEM Analyser report and "color" the graph nodes. Dede-Java shows you that **one specific OSGi Service** is the "Source of Infection" for 400 of those errors. Fix that one service, and 80% of your compliance debt vanishes.

### B. Validating the "Cloud Readiness" of Wiring
AEM Analyser checks if a bundle *can* be installed.
*   **Dede-Java** checks if the bundle *should* be installed. It detects "Architectural Rot"—wiring that is technically legal but makes the system fragile (e.g., circular dependencies between 'core' and 'ui.apps' bundles).

### C. ClientLib "Bloat" Detection
AEM Analyser has limited visibility into the front-end dependency hell.
*   **Dede-Java** parses `cq:ClientLibraryFolder` to detect **Circular Embeds** and **Redundant Dependencies** that cause slow page loads, which AEM Analyser misses entirely.

---

## 4. Feature Roadmap: Ingesting AEM Analyser "DNA"

To make Dede-Java the ultimate tool, we will implement these "Complementary Features":

1.  **Analyser Report Ingestion**: Create a parser for `target/aemanalyser/report.json`. Map those errors directly onto the CodeNodes in the Dede graph.
2.  **Service User Mapping**: AEM Analyser flags "Admin Resource Resolver" usage. Dede-Java will map the **call-chain** that leads to that usage, showing exactly which HTL component is triggering the insecure operation.
3.  **OSGi Configuration Validator**: Ingest AEM Analyser's config checks to show which `@Component` is being misconfigured by which `/apps/.../config` node.

---

## 5. Value Proposition for Productization

If you sell this as a product, your pitch is:
> "AEM Analyser tells you **if** you can go to the cloud. **Dede-Java** tells you **how** to get there without breaking your architecture, and provides the **Guardrails** to stay clean once you arrive."
