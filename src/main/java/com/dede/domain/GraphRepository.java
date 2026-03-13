package com.dede.domain;

import com.dede.domain.model.CodeNode;
import com.dede.domain.model.Relationship;
import com.dede.domain.model.RelationshipType;
import org.jgrapht.Graph;
import org.jgrapht.graph.DirectedMultigraph;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Core storage for the architectural graph.
 */
@Repository
public class GraphRepository {
    private final Graph<CodeNode, Relationship> graph;

    public GraphRepository() {
        this.graph = new DirectedMultigraph<>(Relationship.class);
    }

    public synchronized void addNode(CodeNode node) {
        if (!graph.containsVertex(node)) {
            graph.addVertex(node);
        }
    }

    public synchronized Relationship addEdge(CodeNode source, CodeNode target, RelationshipType type, int confidence) {
        addNode(source);
        addNode(target);

        Relationship existing = graph.getAllEdges(source, target).stream()
                .filter(e -> e.getType() == type)
                .findFirst()
                .orElse(null);

        if (existing == null) {
            Relationship newEdge = new Relationship(type, confidence);
            graph.addEdge(source, target, newEdge);
            return newEdge;
        }
        return existing;
    }

    public Graph<CodeNode, Relationship> getGraph() {
        return graph;
    }

    public Set<CodeNode> getAllNodes() {
        return graph.vertexSet();
    }

    public Optional<CodeNode> findNodeById(String id) {
        return graph.vertexSet().stream()
                .filter(node -> node.getId().equals(id))
                .findFirst();
    }
}
