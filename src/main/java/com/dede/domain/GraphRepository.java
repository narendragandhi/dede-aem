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
    /** id -> the vertex stored in the graph: node lookups run per span, per log entry, per request. */
    private final java.util.Map<String, CodeNode> byId = new java.util.concurrent.ConcurrentHashMap<>();

    public GraphRepository() {
        this.graph = new DirectedMultigraph<>(Relationship.class);
    }

    /**
     * Adds {@code node}, or -- when a node with its id exists (several parsers describe the same
     * resource type or class) -- merges its properties into the stored one without overwriting.
     * Before this merge, the second description's properties were silently dropped.
     */
    public synchronized void addNode(CodeNode node) {
        CodeNode stored = findNodeById(node.getId()).orElse(null);
        if (stored == null) {
            graph.addVertex(node);
            byId.put(node.getId(), node);
        } else if (stored != node) {
            node.getProperties().forEach(stored.getProperties()::putIfAbsent);
        }
    }

    /**
     * Removes all nodes associated with a specific file path.
     * Useful for partial re-scanning in incremental mode.
     */
    public synchronized void removeNodesByFilePath(String filePath) {
        if (filePath == null) return;
        
        Set<CodeNode> nodesToRemove = graph.vertexSet().stream()
                .filter(node -> filePath.equals(node.getFilePath()))
                .collect(Collectors.toSet());
        
        graph.removeAllVertices(nodesToRemove);
        nodesToRemove.forEach(n -> byId.remove(n.getId()));
    }

    public synchronized Relationship addEdge(CodeNode source, CodeNode target, RelationshipType type, int confidence) {
        // Prevent self-loops - JGraphT DirectedMultigraph doesn't allow them by default
        if (source.equals(target) || source.getId().equals(target.getId())) {
            return null;
        }

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
        // getGraph() is public, so the graph can change behind the index: resync when it has.
        if (byId.size() != graph.vertexSet().size()) {
            synchronized (this) {
                byId.clear();
                graph.vertexSet().forEach(n -> byId.put(n.getId(), n));
            }
        }
        return Optional.ofNullable(byId.get(id));
    }
}
