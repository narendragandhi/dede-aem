# dede-java: Architectural Intelligence for Java

`dede-java` is a high-performance dependency explorer designed for massive, modular Java ecosystems (OSGi, AEM, Karaf). It maps explicit code calls and implicit service wiring into a unified impact graph.

[![Spec: BMAD](https://img.shields.io/badge/Spec-BMAD-blue)](docs/prd.md)
[![Design: Hexagonal](https://img.shields.io/badge/Design-Hexagonal-green)](docs/architecture.md)
[![Agent: Embabel](https://img.shields.io/badge/Agent-Embabel-orange)](https://github.com/embabel-agent/embabel)

## 🚀 Key Features
- **Hybrid Discovery**: Unified analysis of local `.java` source and external binary `.jar` dependencies.
- **OSGi Deep-Dive**: Native support for `MANIFEST.MF` package wiring and `OSGI-INF` Declarative Services.
- **O(1) Metadata Caching**: Scalable skip-scanning for 1,000+ bundles using intelligent JSON caching.
- **Agentic reasoning**: Local LLM integration (Ollama) for natural language impact analysis.

## 🏁 Quick Start
See the [Getting Started Guide](docs/getting-started.md) for detailed setup.

### 1. Build
```bash
mvn clean package
```

### 2. Scan a Modular Project
```bash
java -jar target/dede-java-0.0.1-SNAPSHOT.jar /path/to/your/product
```

### 3. Impact Analysis (Agent Mode)
*Note: Requires Ollama running locally.*
```bash
java -jar target/dede-java-0.0.1-SNAPSHOT.jar . --ask "Trace the impact of renaming the BillingService interface"
```

## 🏗️ Architecture
This project follows **Modern System Design** principles:
- **Clean Architecture**: Domain-driven core with Hexagonal ports.
- **Scalability**: Designed to handle products with >1000 OSGi bundles.
- **Documentation**: Managed via [Architectural Decision Records (ADRs)](docs/adr/).

## 📄 License
Apache 2.0
