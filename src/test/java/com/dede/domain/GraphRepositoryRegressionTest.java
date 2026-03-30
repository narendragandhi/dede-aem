package com.dede.domain;

import com.dede.domain.model.CodeNode;
import com.dede.domain.model.NodeType;
import com.dede.domain.model.Relationship;
import com.dede.domain.model.RelationshipType;
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for bugs found during WKND testing.
 */
@DisplayName("Graph Repository Regression Tests")
class GraphRepositoryRegressionTest {

    private GraphRepository repository;
    private GraphService graphService;

    @BeforeEach
    void setUp() {
        repository = new GraphRepository();
        GraphAnalyzer analyzer = new GraphAnalyzer(repository);
        GraphExporter exporter = new GraphExporter(repository);
        graphService = new GraphService(repository, analyzer, exporter);
    }

    @Nested
    @DisplayName("Self-Loop Prevention (Bug #1)")
    class SelfLoopPrevention {

        @Test
        @DisplayName("Should not allow self-loops - same object reference")
        void shouldNotAllowSelfLoopsSameObject() {
            CodeNode node = new CodeNode("test:node", "TestNode", NodeType.CLASS, "Test", null);
            repository.addNode(node);

            // Attempt to add self-loop (requires confidence param)
            Relationship result = repository.addEdge(node, node, RelationshipType.CALLS, 100);

            // Should return null and not crash
            assertThat(result).isNull();
            assertThat(graphService.getEdgeCount()).isZero();
        }

        @Test
        @DisplayName("Should not allow self-loops - same ID different objects")
        void shouldNotAllowSelfLoopsSameId() {
            CodeNode node1 = new CodeNode("test:node", "TestNode", NodeType.CLASS, "Test", null);
            CodeNode node2 = new CodeNode("test:node", "TestNode", NodeType.CLASS, "Test", null);

            repository.addNode(node1);

            // Attempt to add self-loop with different object but same ID
            Relationship result = repository.addEdge(node1, node2, RelationshipType.CALLS, 100);

            assertThat(result).isNull();
            assertThat(graphService.getEdgeCount()).isZero();
        }

        @Test
        @DisplayName("Should still allow normal edges")
        void shouldStillAllowNormalEdges() {
            CodeNode source = new CodeNode("test:source", "Source", NodeType.CLASS, "Source", null);
            CodeNode target = new CodeNode("test:target", "Target", NodeType.CLASS, "Target", null);

            repository.addNode(source);
            repository.addNode(target);

            Relationship result = repository.addEdge(source, target, RelationshipType.CALLS, 100);

            assertThat(result).isNotNull();
            assertThat(graphService.getEdgeCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Self-loop should not throw JGraphT exception")
        void selfLoopShouldNotThrowException() {
            CodeNode node = new CodeNode("method:test", "test", NodeType.METHOD, "test", null);

            // This used to throw: "loops not allowed"
            assertDoesNotThrow(() -> {
                repository.addNode(node);
                repository.addEdge(node, node, RelationshipType.CALLS, 100);
            });
        }
    }

    @Nested
    @DisplayName("Edge Deduplication")
    class EdgeDeduplication {

        @Test
        @DisplayName("Should not duplicate edges of same type")
        void shouldNotDuplicateSameType() {
            CodeNode a = new CodeNode("test:a", "A", NodeType.CLASS, "A", null);
            CodeNode b = new CodeNode("test:b", "B", NodeType.CLASS, "B", null);

            repository.addNode(a);
            repository.addNode(b);

            repository.addEdge(a, b, RelationshipType.CALLS, 100);
            repository.addEdge(a, b, RelationshipType.CALLS, 100);
            repository.addEdge(a, b, RelationshipType.CALLS, 100);

            assertThat(graphService.getEdgeCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should allow multiple edges of different types")
        void shouldAllowDifferentTypes() {
            CodeNode a = new CodeNode("test:a", "A", NodeType.CLASS, "A", null);
            CodeNode b = new CodeNode("test:b", "B", NodeType.CLASS, "B", null);

            repository.addNode(a);
            repository.addNode(b);

            repository.addEdge(a, b, RelationshipType.CALLS, 100);
            repository.addEdge(a, b, RelationshipType.USES, 100);
            repository.addEdge(a, b, RelationshipType.IMPORTS, 100);

            assertThat(graphService.getEdgeCount()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("Node Properties")
    class NodeProperties {

        @Test
        @DisplayName("Should preserve node properties")
        void shouldPreserveProperties() {
            CodeNode node = new CodeNode("test:node", "Node", NodeType.JCR_RESOURCE_TYPE, "Desc", "/path");
            node.getProperties().put("isProxy", "true");
            node.getProperties().put("sling:resourceSuperType", "core/button");

            repository.addNode(node);

            CodeNode retrieved = repository.findNodeById("test:node").orElseThrow();
            assertThat(retrieved.getProperties().get("isProxy")).isEqualTo("true");
            assertThat(retrieved.getProperties().get("sling:resourceSuperType")).isEqualTo("core/button");
        }
    }
}
