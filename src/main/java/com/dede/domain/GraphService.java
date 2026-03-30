package com.dede.domain;

import com.dede.domain.model.CodeNode;
import com.dede.domain.model.Relationship;
import com.dede.domain.model.RelationshipType;
import org.jgrapht.Graph;
import org.springframework.stereotype.Service;

import com.dede.domain.model.NodeType;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Facade for the architectural graph system.
 * Delegates to specialized components for Storage, Analysis, and Exporting.
 */
@Service
public class GraphService {

    private final GraphRepository repository;
    private final GraphAnalyzer analyzer;
    private final GraphExporter exporter;

    public GraphService(GraphRepository repository, GraphAnalyzer analyzer, GraphExporter exporter) {
        this.repository = repository;
        this.analyzer = analyzer;
        this.exporter = exporter;
    }

    // --- Storage ---
    public void addNode(CodeNode node) {
        repository.addNode(node);
    }

    /**
     * Clear all metadata for a specific file before re-scanning it.
     */
    public void removeNodesByFilePath(String filePath) {
        repository.removeNodesByFilePath(filePath);
    }

    public Relationship addEdge(CodeNode source, CodeNode target, RelationshipType type) {
        return repository.addEdge(source, target, type, 100);
    }

    public Relationship addEdge(CodeNode source, CodeNode target, RelationshipType type, int confidence) {
        return repository.addEdge(source, target, type, confidence);
    }

    public Set<CodeNode> getAllNodes() {
        return repository.getAllNodes();
    }

    public Optional<CodeNode> findNodeById(String id) {
        return repository.findNodeById(id);
    }

    public List<CodeNode> findNodesByType(NodeType type) {
        return repository.getAllNodes().stream()
            .filter(node -> node.getType() == type)
            .collect(Collectors.toList());
    }

    public Graph<CodeNode, Relationship> getGraph() {
        return repository.getGraph();
    }

    public int getNodeCount() {
        return repository.getAllNodes().size();
    }

    public int getEdgeCount() {
        return repository.getGraph().edgeSet().size();
    }

    // --- Analysis ---
    public Set<CodeNode> getOutgoingNodes(CodeNode source) {
        return analyzer.getOutgoingNodes(source);
    }

    public Set<CodeNode> getIncomingNodes(CodeNode target) {
        return analyzer.getIncomingNodes(target);
    }

    public Set<CodeNode> getRelatedNodes(CodeNode source, RelationshipType type) {
        return analyzer.getRelatedNodes(source, type);
    }

    public Set<CodeNode> getInboundRelatedNodes(CodeNode target, RelationshipType type) {
        return analyzer.getInboundRelatedNodes(target, type);
    }

    public Set<CodeNode> getTransitiveIncomingNodes(CodeNode target) {
        return analyzer.getTransitiveIncomingNodes(target);
    }

    public Set<Set<CodeNode>> findCycles() {
        return analyzer.findCycles();
    }

    // --- Exporting ---
    public void exportToDot(File file) throws IOException {
        exporter.exportToDot(file);
    }

    public String exportToJson() {
        return exporter.exportToJson();
    }

    public String exportHierarchicalJson() {
        return exporter.exportHierarchicalJson();
    }
}
