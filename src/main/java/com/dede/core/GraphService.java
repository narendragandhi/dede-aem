package com.dede.core;

import com.dede.core.model.CodeNode;
import com.dede.core.model.NodeType;
import com.dede.core.model.Relationship;
import com.dede.core.model.RelationshipType;
import org.jgrapht.Graph;
import org.jgrapht.graph.DirectedMultigraph;
import org.jgrapht.nio.json.JSONExporter;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.io.FileWriter;
import java.io.File;
import java.util.Optional;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
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
        return addEdge(source, target, type, 100);
    }

    public synchronized Relationship addEdge(CodeNode source, CodeNode target, RelationshipType type, int confidence) {
        addNode(source);
        addNode(target);

        // For multigraphs, we find if an edge of this type already exists
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

    public void exportToDot(File file) throws IOException {
        org.jgrapht.nio.dot.DOTExporter<CodeNode, Relationship> exporter = new org.jgrapht.nio.dot.DOTExporter<>(
            node -> node.getId().replaceAll("[^a-zA-Z0-9_]", "_")
        );
        
        exporter.setVertexAttributeProvider(node -> {
            Map<String, org.jgrapht.nio.Attribute> attrs = new LinkedHashMap<>();
            attrs.put("label", org.jgrapht.nio.DefaultAttribute.createAttribute(node.getName()));
            attrs.put("shape", org.jgrapht.nio.DefaultAttribute.createAttribute(node.getType() == NodeType.OSGI_COMPONENT ? "box" : "ellipse"));
            attrs.put("color", org.jgrapht.nio.DefaultAttribute.createAttribute(node.getType().name().startsWith("SLING") ? "blue" : "black"));
            return attrs;
        });
        
        exporter.setEdgeAttributeProvider(edge -> {
            Map<String, org.jgrapht.nio.Attribute> attrs = new LinkedHashMap<>();
            attrs.put("label", org.jgrapht.nio.DefaultAttribute.createAttribute(edge.getType().name()));
            return attrs;
        });
        
        try (FileWriter writer = new FileWriter(file)) {
            exporter.exportGraph(graph, writer);
        }
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
        result.remove(target);
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
        return cycleNodes.isEmpty() ? java.util.Collections.emptySet() : java.util.Collections.singleton(cycleNodes);
    }

    public String exportHierarchicalJson() {
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

    public Graph<CodeNode, Relationship> getGraph() {
        return graph;
    }
}
