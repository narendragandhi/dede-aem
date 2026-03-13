# 🕵️ Dede-Java: High-Performance Architectural Intelligence

**Dede-Java** is a next-generation architectural "Digital Twin" engine for AEM, Sling, and OSGi ecosystems.

---

### 📜 Credits & Inspiration
This project is deeply inspired by and built as a Java-based evolution of the excellent [**dede**](https://github.com/mitkox/dede) tool created by **Mitko Kolev**. 

We maintain full feature parity with the original `dede` while extending its reach into the AEM-specific "Sling Waterfall" (HTL -> Models -> JCR -> Dispatcher).

---

### 🚀 Core Feature Parity (Legacy Dede)
`dede-java` provides 100% of the core capabilities found in the original tool:
*   **OSGi Dependency Analysis**: Traces `Import-Package` and `Export-Package` wires across bundles.
*   **Service Topology**: Maps `@Component` providers to `@Reference` consumers.
*   **Transitive Closure**: Calculates the full impact of a bundle or service across the entire OSGi registry.
*   **Circular Dependency Detection**: Identifies cycles in your service or bundle graphs that cause startup "flapping."
*   **Visual Graphing**: Generates industry-standard DOT files for visualization in Graphviz.

### 🌟 Extended Intelligence (Dede-Java v0.8.x)
Beyond the original OSGi scope, this tool adds:
*   **Sling Waterfall Traceability**: Traces HTL `data-sly-use` ➡️ Sling Models ➡️ OSGi Services.
*   **JCR Content Bridge**: Proves if a Java component is "Zombie Code" by checking for its instantiation in `.content.xml` files.
*   **Dispatcher Reachability**: Links infrastructure `/filter` rules to Java Servlets to identify public attack surfaces.
*   **Security Shield**: XXE-hardened XML parsing and automatic secret sanitization.
*   **AI Refactoring Engine**: Suggests architectural improvements (e.g., God Bundle splitting, Zombie removal).

---

### 🛠️ Quick Start
```bash
# Build the executable
mvn clean package -DskipTests

# Scan an AEM project
java -jar target/dede-java-0.0.1-SNAPSHOT-exec.jar /path/to/project --profiles aem
```

### 📊 Export Formats
*   `architecture.dot`: For visual analysis.
*   `architecture.json`: For web-based D3.js or Sigma.js dashboards.
*   `hierarchical.json`: Optimized for Sunburst or Tree-map visualizations.
