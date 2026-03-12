package com.dede.core;

import com.dede.core.model.CodeNode;
import com.dede.core.model.NodeType;
import com.dede.core.model.Relationship;
import com.dede.core.model.RelationshipType;
import org.jgrapht.Graph;
import org.jgrapht.graph.DirectedMultigraph;
import org.jgrapht.nio.json.JSONExporter;
import org.springframework.stereotype.Service;

import java.io.StringWriter;
import java.io.Writer;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class GraphService {

    private final Graph<CodeNode, Relationship> graph;

    public GraphService() {
        this.graph = new DirectedMultigraph<>(Relationship.class);
    }

    public synchronized void addNode(CodeNode node) {
        if (!graph.containsVertex(node)) {
            graph.addVertex(node);
        }
    }

    public synchronized Relationship addEdge(CodeNode source, CodeNode target, RelationshipType type) {
        addNode(source);
        addNode(target);

        Relationship edge = new Relationship(type);
        if (!graph.containsEdge(source, target)) {
            graph.addEdge(source, target, edge);
        } else {
            edge = graph.getAllEdges(source, target).stream()
                    .filter(e -> e.getType() == type)
                    .findFirst()
                    .orElseGet(() -> {
                        Relationship newEdge = new Relationship(type);
                        graph.addEdge(source, target, newEdge);
                        return newEdge;
                    });
        }
        return edge;
    }

    public Set<CodeNode> getOutgoingNodes(CodeNode source) {
        return graph.outgoingEdgesOf(source).stream()
                .map(graph::getEdgeTarget)
                .collect(Collectors.toSet());
    }

    public Set<CodeNode> getIncomingNodes(CodeNode target) {
        return graph.incomingEdgesOf(target).stream()
                .map(graph::getEdgeSource)
                .collect(Collectors.toSet());
    }

    public Set<CodeNode> getTransitiveIncomingNodes(CodeNode target) {
        Set<CodeNode> result = new java.util.HashSet<>();
        collectTransitiveInbound(target, result);
        result.remove(target); // Don't include self
        return result;
    }

    private void collectTransitiveInbound(CodeNode node, Set<CodeNode> visited) {
        if (visited.contains(node)) return;
        visited.add(node);
        for (Relationship edge : graph.incomingEdgesOf(node)) {
            collectTransitiveInbound(graph.getEdgeSource(edge), visited);
        }
    }

    public Set<Relationship> getEdgesOf(CodeNode node, RelationshipType type, boolean outgoing) {
        Set<Relationship> edges = outgoing ? graph.outgoingEdgesOf(node) : graph.incomingEdgesOf(node);
        return edges.stream()
                .filter(e -> e.getType() == type)
                .collect(Collectors.toSet());
    }

    public Set<CodeNode> getRelatedNodes(CodeNode source, RelationshipType type) {
        return graph.outgoingEdgesOf(source).stream()
                .filter(edge -> edge.getType() == type)
                .map(graph::getEdgeTarget)
                .collect(Collectors.toSet());
    }

    public Set<CodeNode> getInboundRelatedNodes(CodeNode target, RelationshipType type) {
        return graph.incomingEdgesOf(target).stream()
                .filter(edge -> edge.getType() == type)
                .map(graph::getEdgeSource)
                .collect(Collectors.toSet());
    }

    public Set<CodeNode> getAllNodes() {
        return graph.vertexSet();
    }
    
    public Optional<CodeNode> findNodeById(String id) {
        return graph.vertexSet().stream()
                .filter(node -> node.getId().equals(id))
                .findFirst();
    }

    public Set<Set<CodeNode>> findCycles() {
        org.jgrapht.alg.cycle.CycleDetector<CodeNode, Relationship> cycleDetector = new org.jgrapht.alg.cycle.CycleDetector<>(graph);
        Set<CodeNode> cycleNodes = cycleDetector.findCycles();
        // Simplified grouping - in reality, we'd find actual cycle paths
        return cycleNodes.isEmpty() ? java.util.Collections.emptySet() : java.util.Collections.singleton(cycleNodes);
    }

    public String exportHierarchicalJson() {
        // Cytoscape 'parent' support
        // We ensure every node has a 'parent' property if applicable
        graph.vertexSet().forEach(node -> {
            if (node.getType() == NodeType.PACKAGE) {
                findParentBundle(node).ifPresent(b -> node.getProperties().put("parent", b.getId()));
            } else if (node.getType() == NodeType.CLASS || node.getType() == NodeType.INTERFACE || node.getType() == NodeType.SLING_MODEL) {
                findParentPackage(node).ifPresent(p -> node.getProperties().put("parent", p.getId()));
            }
        });
        return exportToJson();
    }

    private Optional<CodeNode> findParentBundle(CodeNode pkg) {
        return graph.incomingEdgesOf(pkg).stream()
                .filter(e -> e.getType() == RelationshipType.EXPORTS)
                .map(graph::getEdgeSource)
                .findFirst();
    }

    private Optional<CodeNode> findParentPackage(CodeNode clazz) {
        return graph.incomingEdgesOf(clazz).stream()
                .filter(e -> e.getType() == RelationshipType.CONTAINS)
                .map(graph::getEdgeSource)
                .findFirst();
    }

    public String exportToJson() {
        JSONExporter<CodeNode, Relationship> exporter = new JSONExporter<>(CodeNode::getId);
        Writer writer = new StringWriter();
        exporter.exportGraph(graph, writer);
        return writer.toString();
    }
    
    public int getNodeCount() {
        return graph.vertexSet().size();
    }
    
    public int getEdgeCount() {
        return graph.edgeSet().size();
    }
}
