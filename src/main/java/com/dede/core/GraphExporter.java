package com.dede.core;

import com.dede.core.model.CodeNode;
import com.dede.core.model.NodeType;
import com.dede.core.model.Relationship;
import com.dede.core.model.RelationshipType;
import org.jgrapht.nio.dot.DOTExporter;
import org.jgrapht.nio.json.JSONExporter;
import org.jgrapht.nio.DefaultAttribute;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Exporter for graph visualizations.
 */
@Service
public class GraphExporter {

    private final GraphRepository repository;

    public GraphExporter(GraphRepository repository) {
        this.repository = repository;
    }

    public void exportToDot(File file) throws IOException {
        DOTExporter<CodeNode, Relationship> exporter = new DOTExporter<>(
            node -> node.getId().replaceAll("[^a-zA-Z0-9_]", "_")
        );
        
        exporter.setVertexAttributeProvider(node -> {
            Map<String, org.jgrapht.nio.Attribute> attrs = new LinkedHashMap<>();
            attrs.put("label", DefaultAttribute.createAttribute(node.getName()));
            attrs.put("shape", DefaultAttribute.createAttribute(node.getType() == NodeType.OSGI_COMPONENT ? "box" : "ellipse"));
            attrs.put("color", DefaultAttribute.createAttribute(node.getType().name().startsWith("SLING") ? "blue" : "black"));
            return attrs;
        });
        
        exporter.setEdgeAttributeProvider(edge -> {
            Map<String, org.jgrapht.nio.Attribute> attrs = new LinkedHashMap<>();
            attrs.put("label", DefaultAttribute.createAttribute(edge.getType().name()));
            return attrs;
        });
        
        try (FileWriter writer = new FileWriter(file)) {
            exporter.exportGraph(repository.getGraph(), writer);
        }
    }

    public String exportToJson() {
        JSONExporter<CodeNode, Relationship> exporter = new JSONExporter<>(CodeNode::getId);
        Writer writer = new StringWriter();
        exporter.exportGraph(repository.getGraph(), writer);
        return writer.toString();
    }

    public String exportHierarchicalJson() {
        repository.getGraph().vertexSet().forEach(node -> {
            if (node.getType() == NodeType.PACKAGE) {
                findParentBundle(node).ifPresent(b -> node.getProperties().put("parent", b.getId()));
            } else if (node.getType() == NodeType.CLASS || node.getType() == NodeType.INTERFACE || node.getType() == NodeType.SLING_MODEL) {
                findParentPackage(node).ifPresent(p -> node.getProperties().put("parent", p.getId()));
            }
        });
        return exportToJson();
    }

    private Optional<CodeNode> findParentBundle(CodeNode pkg) {
        return repository.getGraph().incomingEdgesOf(pkg).stream()
                .filter(e -> e.getType() == RelationshipType.EXPORTS)
                .map(repository.getGraph()::getEdgeSource)
                .findFirst();
    }

    private Optional<CodeNode> findParentPackage(CodeNode clazz) {
        return repository.getGraph().incomingEdgesOf(clazz).stream()
                .filter(e -> e.getType() == RelationshipType.CONTAINS)
                .map(repository.getGraph()::getEdgeSource)
                .findFirst();
    }
}
