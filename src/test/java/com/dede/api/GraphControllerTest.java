package com.dede.api;

import com.dede.domain.GraphAnalyzer;
import com.dede.domain.GraphExporter;
import com.dede.domain.GraphRepository;
import com.dede.domain.GraphService;
import com.dede.domain.model.CodeNode;
import com.dede.domain.model.NodeType;
import com.dede.domain.model.RelationshipType;
import com.dede.exception.ErrorCode;
import com.dede.exception.GraphException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("GraphController Unit Tests")
class GraphControllerTest {

    private GraphController controller;
    private GraphService graphService;

    @BeforeEach
    void setUp() {
        GraphRepository repository = new GraphRepository();
        GraphAnalyzer analyzer = new GraphAnalyzer(repository);
        GraphExporter exporter = new GraphExporter(repository);
        graphService = new GraphService(repository, analyzer, exporter);
        controller = new GraphController(graphService);

        // Add sample data
        CodeNode pkg = new CodeNode("pkg:com.test", "com.test", NodeType.PACKAGE, "com.test", null);
        CodeNode clazz = new CodeNode("class:TestClass", "TestClass", NodeType.CLASS, "com.test.TestClass", "/src/Test.java");
        graphService.addEdge(pkg, clazz, RelationshipType.CONTAINS);
    }

    @Test
    @DisplayName("getGraph should return JSON graph")
    void shouldReturnGraph() {
        ResponseEntity<String> response = controller.getGraph();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotBlank();
    }

    @Test
    @DisplayName("getStats should return node and edge counts")
    void shouldReturnStats() {
        ResponseEntity<Map<String, Object>> response = controller.getStats();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).containsKey("nodeCount");
        assertThat(response.getBody()).containsKey("edgeCount");
        assertThat((Integer) response.getBody().get("nodeCount")).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("getNode should return node when found")
    void shouldReturnNodeWhenFound() {
        ResponseEntity<CodeNode> response = controller.getNode("class:TestClass");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getId()).isEqualTo("class:TestClass");
        assertThat(response.getBody().getName()).isEqualTo("TestClass");
    }

    @Test
    @DisplayName("getNode should throw GraphException when not found")
    void shouldThrowWhenNodeNotFound() {
        assertThatThrownBy(() -> controller.getNode("non:existent"))
            .isInstanceOf(GraphException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NODE_NOT_FOUND);
    }

    @Test
    @DisplayName("getIncomingNodes should return incoming nodes")
    void shouldReturnIncomingNodes() {
        ResponseEntity<Set<CodeNode>> response = controller.getIncomingNodes("class:TestClass");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().iterator().next().getId()).isEqualTo("pkg:com.test");
    }

    @Test
    @DisplayName("getOutgoingNodes should return outgoing nodes")
    void shouldReturnOutgoingNodes() {
        ResponseEntity<Set<CodeNode>> response = controller.getOutgoingNodes("pkg:com.test");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().iterator().next().getId()).isEqualTo("class:TestClass");
    }

    @Test
    @DisplayName("findCycles should return empty when no cycles")
    void shouldReturnEmptyWhenNoCycles() {
        ResponseEntity<Set<Set<CodeNode>>> response = controller.findCycles();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    @DisplayName("getHierarchicalGraph should return hierarchical JSON")
    void shouldReturnHierarchicalGraph() {
        ResponseEntity<String> response = controller.getHierarchicalGraph();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotBlank();
    }
}
