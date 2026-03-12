# Dede-Java: The Architectural Intelligence Platform for AEM

## 1. Overview
Dede-Java is a high-performance architectural scanner and intelligence engine designed for complex AEM and OSGi-based ecosystems. It transforms static source code and artifacts into a **Directed Multigraph**, enabling deep traceability and impact analysis.

---

## 2. Key Capabilities

### 🔍 Topological Mapping
Maps the connectivity of the entire AEM stack:
*   **HTL Templates** ➡️ **Sling Models** ➡️ **OSGi Services** ➡️ **OSGi Bundles** ➡️ **ClientLibs**.

### ⚖️ Architectural Guardrails (Governance)
Enforce design rules via `dede-rules.json`.
*   **Example**: Ban legacy `com.day.cq` APIs.
*   **Example**: Prevent circular dependencies between Core and UI bundles.

### 🤖 AI-Driven Refactoring
The agentic layer analyzes graph topology to identify "Architectural Smells":
*   **God Bundles**: High coupling detection.
*   **Dead Code**: Dangling OSGi services.
*   **Modernization**: Automation of AEM Cloud readiness steps.

### 🛡️ Security Reachability Analysis
Go beyond simple CVE lists. Dede-Java traces vulnerabilities from a library to the public UI (HTL), allowing you to prioritize risks by their **Blast Radius**.

---

## 3. Getting Started

### 🚀 Local Execution (CLI)
Build the project:
```bash
mvn clean package -DskipTests
```
Run a scan:
```bash
java -jar target/dede-java-0.0.1-SNAPSHOT-exec.jar <path-to-aem-project> --security --rules my-rules.json
```

### 🐳 Docker Deployment
```bash
docker build -t dede-java .
docker run -v $(pwd):/app/scan dede-java /app/scan --security
```

### 🧩 AEM / OSGi Deployment
1.  Deploy `target/dede-java-0.0.1-SNAPSHOT.jar` to AEM via the Web Console.
2.  Use the `DedeOsgiScanner` service from the AEM Groovy Console or MCP to perform live runtime analysis.

---

## 4. Architectural Documentation
For a deep dive into the engine's design, see:
*   [Architecture Guide](docs/architecture.md)
*   [Security Reachability Analysis](docs/SECURITY_AUDIT.md)
*   [AEM Analyser Complement](docs/AEM_ANALYSER_COMPLIMENT.md)
