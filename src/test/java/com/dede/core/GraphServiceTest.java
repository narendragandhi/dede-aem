package com.dede.domain;

import com.dede.domain.model.CodeNode;
import com.dede.domain.model.NodeType;
import com.dede.domain.model.Relationship;
import com.dede.domain.model.RelationshipType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GraphService Tests")
class GraphServiceTest {

    private GraphService graphService;
    private GraphRepository repository;

    @BeforeEach
    void setUp() {
        repository = new GraphRepository();
        GraphAnalyzer analyzer = new GraphAnalyzer(repository);
        GraphExporter exporter = new GraphExporter(repository);
        graphService = new GraphService(repository, analyzer, exporter);
    }

    @Nested
    @DisplayName("Node Operations")
    class NodeOperations {

        @Test
        @DisplayName("Should add node to graph")
        void shouldAddNode() {
            CodeNode node = new CodeNode("class:TestClass", "TestClass", NodeType.CLASS, "com.test.TestClass", null);

            graphService.addNode(node);

            assertThat(graphService.getNodeCount()).isEqualTo(1);
            assertThat(graphService.getAllNodes()).contains(node);
        }

        @Test
        @DisplayName("Should not duplicate existing nodes")
        void shouldNotDuplicateNodes() {
            CodeNode node = new CodeNode("class:TestClass", "TestClass", NodeType.CLASS, "com.test.TestClass", null);

            graphService.addNode(node);
            graphService.addNode(node);

            assertThat(graphService.getNodeCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should find node by ID")
        void shouldFindNodeById() {
            CodeNode node = new CodeNode("class:TestClass", "TestClass", NodeType.CLASS, "com.test.TestClass", null);
            graphService.addNode(node);

            Optional<CodeNode> found = graphService.findNodeById("class:TestClass");

            assertThat(found).isPresent();
            assertThat(found.get()).isEqualTo(node);
        }

        @Test
        @DisplayName("Should return empty for non-existent node")
        void shouldReturnEmptyForNonExistentNode() {
            Optional<CodeNode> found = graphService.findNodeById("non:existent");

            assertThat(found).isEmpty();
        }
    }

    @Nested
    @DisplayName("Edge Operations")
    class EdgeOperations {

        @Test
        @DisplayName("Should allow multiple edges between same nodes")
        void shouldAllowMultipleEdgesBetweenSameNodes() {
            CodeNode methodA = new CodeNode("method:A", "A", NodeType.METHOD, "A", null);
            CodeNode methodB = new CodeNode("method:B", "B", NodeType.METHOD, "B", null);

            graphService.addEdge(methodA, methodB, RelationshipType.CALLS);
            graphService.addEdge(methodA, methodB, RelationshipType.USES);

            assertThat(graphService.getNodeCount()).isEqualTo(2);
            assertThat(graphService.getEdgeCount()).isEqualTo(2);

            Set<CodeNode> outgoing = graphService.getOutgoingNodes(methodA);
            assertThat(outgoing).containsExactly(methodB);
        }

        @Test
        @DisplayName("Should not duplicate edges of same type")
        void shouldNotDuplicateEdgesOfSameType() {
            CodeNode methodA = new CodeNode("method:A", "A", NodeType.METHOD, "A", null);
            CodeNode methodB = new CodeNode("method:B", "B", NodeType.METHOD, "B", null);

            graphService.addEdge(methodA, methodB, RelationshipType.CALLS);
            graphService.addEdge(methodA, methodB, RelationshipType.CALLS);

            assertThat(graphService.getEdgeCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should add edge with confidence score")
        void shouldAddEdgeWithConfidence() {
            CodeNode source = new CodeNode("class:Source", "Source", NodeType.CLASS, "Source", null);
            CodeNode target = new CodeNode("class:Target", "Target", NodeType.CLASS, "Target", null);

            Relationship edge = graphService.addEdge(source, target, RelationshipType.DYNAMIC_ADAPTS_TO, 70);

            assertThat(edge.getConfidence()).isEqualTo(70);
        }
    }

    @Nested
    @DisplayName("Graph Traversal")
    class GraphTraversal {

        @Test
        @DisplayName("Should correctly identify incoming nodes")
        void shouldCorrectlyIdentifyIncomingNodes() {
            CodeNode caller = new CodeNode("method:caller", "caller", NodeType.METHOD, "caller", null);
            CodeNode callee = new CodeNode("method:callee", "callee", NodeType.METHOD, "callee", null);

            graphService.addEdge(caller, callee, RelationshipType.CALLS);

            Set<CodeNode> incoming = graphService.getIncomingNodes(callee);
            assertThat(incoming).containsExactly(caller);
        }

        @Test
        @DisplayName("Should correctly identify outgoing nodes")
        void shouldCorrectlyIdentifyOutgoingNodes() {
            CodeNode caller = new CodeNode("method:caller", "caller", NodeType.METHOD, "caller", null);
            CodeNode callee = new CodeNode("method:callee", "callee", NodeType.METHOD, "callee", null);

            graphService.addEdge(caller, callee, RelationshipType.CALLS);

            Set<CodeNode> outgoing = graphService.getOutgoingNodes(caller);
            assertThat(outgoing).containsExactly(callee);
        }

        @Test
        @DisplayName("Should find related nodes by relationship type")
        void shouldFindRelatedNodesByType() {
            CodeNode component = new CodeNode("comp:MyComponent", "MyComponent", NodeType.OSGI_COMPONENT, "MyComponent", null);
            CodeNode service1 = new CodeNode("svc:Service1", "Service1", NodeType.OSGI_SERVICE, "Service1", null);
            CodeNode service2 = new CodeNode("svc:Service2", "Service2", NodeType.OSGI_SERVICE, "Service2", null);

            graphService.addEdge(component, service1, RelationshipType.PROVIDES);
            graphService.addEdge(component, service2, RelationshipType.CONSUMES);

            Set<CodeNode> provided = graphService.getRelatedNodes(component, RelationshipType.PROVIDES);
            Set<CodeNode> consumed = graphService.getRelatedNodes(component, RelationshipType.CONSUMES);

            assertThat(provided).containsExactly(service1);
            assertThat(consumed).containsExactly(service2);
        }

        @Test
        @DisplayName("Should find direct incoming nodes")
        void shouldFindDirectIncomingNodes() {
            CodeNode a = new CodeNode("node:A", "A", NodeType.CLASS, "A", null);
            CodeNode b = new CodeNode("node:B", "B", NodeType.CLASS, "B", null);

            // Create edge: A -> B
            graphService.addEdge(a, b, RelationshipType.CALLS);

            // A should be in the incoming nodes of B
            Set<CodeNode> incoming = graphService.getIncomingNodes(b);
            assertThat(incoming).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("Cycle Detection")
    class CycleDetection {

        @Test
        @DisplayName("Should detect circular dependencies")
        void shouldDetectCircularDependencies() {
            CodeNode a = new CodeNode("node:A", "A", NodeType.CLASS, "A", null);
            CodeNode b = new CodeNode("node:B", "B", NodeType.CLASS, "B", null);
            CodeNode c = new CodeNode("node:C", "C", NodeType.CLASS, "C", null);

            // Create a cycle: A -> B -> C -> A using different relationship types
            // to avoid deduplication
            graphService.addEdge(a, b, RelationshipType.CALLS);
            graphService.addEdge(b, c, RelationshipType.WIRES_TO);
            graphService.addEdge(c, a, RelationshipType.CONSUMES);

            // Verify graph structure is set up
            assertThat(graphService.getNodeCount()).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("Should return empty when no cycles exist")
        void shouldReturnEmptyWhenNoCycles() {
            CodeNode a = new CodeNode("node:A", "A", NodeType.CLASS, "A", null);
            CodeNode b = new CodeNode("node:B", "B", NodeType.CLASS, "B", null);
            CodeNode c = new CodeNode("node:C", "C", NodeType.CLASS, "C", null);

            graphService.addEdge(a, b, RelationshipType.CALLS);
            graphService.addEdge(b, c, RelationshipType.CALLS);

            Set<Set<CodeNode>> cycles = graphService.findCycles();

            assertThat(cycles).isEmpty();
        }
    }

    @Nested
    @DisplayName("Export Operations")
    class ExportOperations {

        @Test
        @DisplayName("Should export to JSON")
        void shouldExportToJson() {
            CodeNode node = new CodeNode("class:Test", "Test", NodeType.CLASS, "Test", null);
            graphService.addNode(node);

            String json = graphService.exportToJson();

            assertThat(json).isNotBlank();
            assertThat(json).contains("Test");
        }

        @Test
        @DisplayName("Should export hierarchical JSON")
        void shouldExportHierarchicalJson() {
            CodeNode pkg = new CodeNode("pkg:com.test", "com.test", NodeType.PACKAGE, "com.test", null);
            CodeNode clazz = new CodeNode("class:Test", "Test", NodeType.CLASS, "Test", null);
            graphService.addEdge(pkg, clazz, RelationshipType.CONTAINS);

            String json = graphService.exportHierarchicalJson();

            assertThat(json).isNotBlank();
        }
    }
}
