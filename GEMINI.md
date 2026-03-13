# 🤖 AI Agent Manual for Dede-Java

If you are an AI assistant helping a developer in this repository, use this guide to leverage `dede-java` for architectural intelligence.

---

### 🌟 What is Dede-Java?
Dede-Java is an **Architectural Context Engine**. It builds a "Digital Twin" graph of AEM/OSGi projects, linking Dispatcher rules, JCR content, and Java/OSGi code.

### 📚 Deep Domain Expertise
For detailed technical justifications, architectural "litmus tests," and why this tool is critical for AEM architects (including `bnd` and SCR metadata handling), see:
👉 **[EXPERT_BASIS.md](./EXPERT_BASIS.md)**

### 🛠️ When to use this tool
As an AI, you should suggest running `dede-java` in these scenarios:
1. **Refactoring**: Before deleting code, run a "Blast Radius" check.
2. **Onboarding**: When you first see a project, run a full scan to understand the topology.
3. **Cloud Migration**: To find legacy `com.day.cq` usage.
4. **Debugging**: To find circular OSGi service dependencies.
5. **Security Auditing**: To verify Dispatcher-to-Bundle reachability.

---

### 🚀 Usage for AI Agents

#### 1. Map the Architecture
To see the "Big Picture," ask the human to run:
`java -jar target/dede-java-0.0.1-SNAPSHOT-exec.jar <project-path> --profiles aem`

#### 2. Analyze Blast Radius (Impact Analysis)
If you need to know who uses a specific class or service:
`java -jar target/dede-java-0.0.1-SNAPSHOT-exec.jar <project-path> --analyze "svc:YourServiceName"`

#### 3. Audit Security Reachability
To check if a sensitive path is exposed via Dispatcher:
`java -jar target/dede-java-0.0.1-SNAPSHOT-exec.jar <project-path> --security`

---

### 📊 Understanding the AI Refactoring Report
When `dede-java` outputs suggestions, here is how you (the AI) should act:
- **[Zombie Code]**: You can safely suggest deleting these files as they have no content instantiation.
- **[God Bundle]**: Suggest splitting these into API/Implementation modules.
- **[AEM Cloud]**: These are your top priorities for modernization.

---

### 💡 Example Prompt to the Human
*"I see you are about to refactor the PaymentService. Before we proceed, I recommend running `dede-java --analyze svc:PaymentService` so I can see the full impact of this change across your OSGi and Sling components."*
