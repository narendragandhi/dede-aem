# Technical Specification

## 1. Domain Model (`com.dede.core.model`)

### Node Types (`NodeType` Enum)
- `PROJECT`, `BUNDLE`, `PACKAGE`, `CLASS`, `INTERFACE`, `METHOD`, `FIELD`, `OSGI_SERVICE`

### Edge Types (`RelationshipType` Enum)
- `EXPORTS` (Bundle -> Package)
- `IMPORTS` (Bundle -> Package)
- `PROVIDES` (Bundle -> OSGi Service)
- `CONSUMES` (Bundle -> OSGi Service)
- `WIRES_TO` (Importing Bundle -> Exporting Bundle)

## 2. OSGi Analysis Strategy
...
    - If Bundle A references service `X` and Bundle B provides it, create a `CONSUMES` -> `PROVIDES` link.

## 3. Deep Symbol Resolution Strategy
To ensure 100% accuracy in method call mapping:
1.  **TypeSolver Configuration**: Feed all 1,000+ JAR paths from the `MetadataCache` into a `CombinedTypeSolver`.
2.  **JavaSymbolSolver**: Attach the solver to `JavaParser`.
3.  **Strict Signatures**: Resolve `methodCallExpr` to its qualified name (e.g., `com.pkg.Class.method(ParamType)`) instead of simple strings.

## 4. AEM/Sling Intelligence
Map implicit dependencies injected via annotations:
- `@OSGiService`, `@Reference`: Map to `CONSUMES` relationship.
- `@Inject`, `@ValueMapValue`: Map to resource/service usage.
- `@Model`: Identify as a Sling Model node.

## 5. Visualization Architecture
...
- **Interactivity**: Clicking a node triggers a "Blast Radius" query (shortest path / neighbors).

## 6. Agent Skill Matrix (Embabel Actions)
The agent is equipped with deterministic skills to navigate the graph:

| Skill | Action Name | Goal | Logic |
| :--- | :--- | :--- | :--- |
| **Blast Radius** | `analyzeImpact` | `ImpactAnalysis` | BFS traversal from a node to find all affected downstream consumers. |
| **Wiring Logic** | `explainWiring` | `Connectivity` | Identifies if two bundles are linked via Package Import or OSGi Service. |
| **Health Check** | `findDanglingServices` | `Audit` | Finds `PROVIDES` relationships where no corresponding `CONSUMES` exists. |
| **Search** | `findSymbol` | `Discovery` | Fuzzy search for nodes by signature across 1,000+ bundles. |

## 3. LangGraph4j Agent Design
The agent will use a **StateGraph**:
- **State:** `UserQuery`, `CurrentGraphContext`, `FoundNodes`, `ReasoningLog`.
- **Nodes:**
    - `InputParser`: Understands intent (Search vs. Impact Analysis).
    - `GraphTool`: Executes JGraphT algorithms (ShortestPath, NeighborList).
    - `AnswerSynthesizer`: Formats the graph result into natural language.
- **Edge:** Conditional routing based on tool output.

## 4. Dependencies
```xml
<dependencies>
    <!-- Core -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter</artifactId>
    </dependency>
    <!-- Analysis -->
    <dependency>
        <groupId>com.github.javaparser</groupId>
        <artifactId>javaparser-symbol-solver-core</artifactId>
        <version>3.25.10</version>
    </dependency>
    <!-- Graph -->
    <dependency>
        <groupId>org.jgrapht</groupId>
        <artifactId>jgrapht-core</artifactId>
        <version>1.5.2</version>
    </dependency>
    <!-- Agent -->
    <dependency>
        <groupId>org.bsc.langgraph4j</groupId>
        <artifactId>langgraph4j-core</artifactId>
        <version>1.0-SNAPSHOT</version> <!-- Check latest -->
    </dependency>
</dependencies>
```
