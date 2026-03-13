# 🎙️ Pitch: Why your AEM Architects need Dede-Java

If you've been working with AEM for 5+ years, you know the "Hidden Debt" problem. Here is how to convince your team to adopt this tool.

---

### 1. "The Zombie Cleanup" 🧟
**The Problem**: AEM projects accumulate hundreds of Sling Models and Components over time. Are they all used? Probably not.
**The Solution**: Dede-Java links JCR content instances (`.content.xml`) to Java code. It lists exactly which models have **Zero** usage in the repository.
> *"Let's stop maintaining code that doesn't actually render anything."*

### 2. "Cloud Migration Insurance" ☁️
**The Problem**: Moving to AEM as a Cloud Service (AEMaaCS) breaks legacy `com.day.cq` and `com.adobe.granite.workflow` APIs.
**The Solution**: Run Dede with the `--profiles aem` flag. It generates a "Blast Radius" report of every legacy API usage that needs refactoring.
> *"We can map our entire migration effort in 30 seconds."*

### 3. "Circular Dependency Buster" 🔄
**The Problem**: OSGi bundles getting stuck in "Starting" or "Installed" state because of circular service references.
**The Solution**: Dede-Java uses JGraphT's Cycle Detection to identify loops in your service wiring that standard IDEs miss.
> *"No more random startup flapping on Author instances."*

### 4. "Reachability-First Security" 🛡️
**The Problem**: Your security scanner found a CVE in a library. But can an anonymous user actually reach that code?
**The Solution**: Dede-Java traces the path: **Dispatcher Filter** ➡️ **Sling Servlet** ➡️ **OSGi Service** ➡️ **Library**. If there is no path, the risk is lower.
> *"Let's prioritize fixes based on real-world reachability, not just scan counts."*

---

### 🛠️ Professional Standards
*   **Java 21**: Built on the latest LTS for maximum performance and future-proofing.
*   **SOLID Design**: Pluggable parser architecture (Strategy Pattern) and decoupled graph storage.
*   **Javadocs**: Full API documentation for team extensibility.
*   **Zero Noise**: Precision scoring ensures we don't flag shared library APIs as "Dead Code."
