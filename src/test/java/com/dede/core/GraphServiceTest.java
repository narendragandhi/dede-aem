package com.dede.core;

import com.dede.core.model.CodeNode;
import com.dede.core.model.NodeType;
import com.dede.core.model.RelationshipType;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GraphServiceTest {

    private GraphService createService() {
        GraphRepository repo = new GraphRepository();
        GraphAnalyzer analyzer = new GraphAnalyzer(repo);
        GraphExporter exporter = new GraphExporter(repo);
        return new GraphService(repo, analyzer, exporter);
    }

    @Test
    void shouldAllowMultipleEdgesBetweenSameNodes() {
        // Arrange
        GraphService graphService = createService();
        CodeNode methodA = new CodeNode("method:A", "A", NodeType.METHOD, "A", null);
        CodeNode methodB = new CodeNode("method:B", "B", NodeType.METHOD, "B", null);

        // Act
        graphService.addEdge(methodA, methodB, RelationshipType.CALLS);
        graphService.addEdge(methodA, methodB, RelationshipType.USES);

        // Assert
        assertThat(graphService.getNodeCount()).isEqualTo(2);
        assertThat(graphService.getEdgeCount()).isEqualTo(2); // Multigraph allows this
        
        Set<CodeNode> outgoing = graphService.getOutgoingNodes(methodA);
        assertThat(outgoing).containsExactly(methodB);
    }

    @Test
    void shouldCorrectyIdentifyIncomingNodes() {
        // Arrange
        GraphService graphService = createService();
        CodeNode caller = new CodeNode("method:caller", "caller", NodeType.METHOD, "caller", null);
        CodeNode callee = new CodeNode("method:callee", "callee", NodeType.METHOD, "callee", null);

        // Act
        graphService.addEdge(caller, callee, RelationshipType.CALLS);

        // Assert
        Set<CodeNode> incoming = graphService.getIncomingNodes(callee);
        assertThat(incoming).containsExactly(caller);
    }
}
