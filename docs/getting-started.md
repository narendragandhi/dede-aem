# Getting Started with dede-java

This guide will walk you through setting up `dede-java` for high-performance dependency exploration.

## 🛠️ Prerequisites
- **Java 21** (Required for Virtual Thread and Record support)
- **Maven 3.9+**
- **Ollama** (Optional, for Agentic features)

## 📦 Installation & Build

1. Clone the repository.
2. Build the executable JAR:
```bash
mvn clean package
```

## 🔍 Scanning Your Codebase

### Standard Local Scan
To scan a standard Maven project or a directory of Java files:
```bash
java -jar target/dede-java-0.0.1-SNAPSHOT.jar /path/to/project
```

### OSGi / Hybrid Scan
If you have a project with 1000+ bundles (e.g., an AEM environment), ensure the platform JARs are in a directory within your scan path. `dede-java` will:
1. Parse your local source code.
2. Index the external JARs using the **Metadata Cache**.
3. Link them via `WIRES_TO` relationships.

### The Metadata Cache
On the first run, a `.dede-cache.json` file is created. This file stores the OSGi metadata of all scanned JARs. Subsequent runs will skip parsing these JARs unless they have changed (validated by file size and timestamp), resulting in near-instant scans for massive platforms.

## 🤖 Using the Agent (Impact Analysis)

`dede-java` integrates with **Embabel** and **Ollama** to provide a conversational interface to your code graph.

1. **Start Ollama**: `ollama serve`
2. **Pull a Model**: `ollama pull mistral`
3. **Run a Query**:
```bash
java -jar target/dede-java-0.0.1-SNAPSHOT.jar . --ask "Who consumes the UserProfileService OSGi service?"
```

## 📂 Project Structure
- `src/main/java/com/dede/core`: Graph engine and Caching logic.
- `src/main/java/com/dede/analysis`: Specialized parsers (Java, Manifest, DS).
- `src/main/java/com/dede/cli`: Spring Boot command-line entry point.
- `docs/adr`: Architectural Decision Records.
- `docs/stories`: Implementation task logs (Beads).
