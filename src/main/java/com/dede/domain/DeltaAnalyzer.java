package com.dede.domain;

import com.dede.domain.model.CodeNode;
import com.dede.domain.model.Relationship;
import com.dede.domain.model.RelationshipType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jgrapht.Graph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Delta Analyzer - Compares graph snapshots to detect changes.
 * Answers: "What changed since last scan?"
 * 
 * Features:
 * - Save/load graph snapshots
 * - Compare two snapshots to find added/removed/modified nodes and edges
 * - Generate delta reports for CI/CD pipelines
 * - Track architectural drift over time
 */
@Service
public class DeltaAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(DeltaAnalyzer.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final GraphService graphService;

    public DeltaAnalyzer(GraphService graphService) {
        this.graphService = graphService;
    }

    /**
     * Save current graph state as a snapshot.
     */
    public String saveSnapshot(Path directory) throws IOException {
        Files.createDirectories(directory);
        
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path snapshotFile = directory.resolve("dede-snapshot-" + timestamp + ".json");
        
        GraphSnapshot snapshot = createSnapshot();
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(snapshotFile.toFile(), snapshot);
        
        log.info("Saved snapshot to: {} ({} nodes, {} edges)", 
            snapshotFile, snapshot.getNodes().size(), snapshot.getEdges().size());
        
        return snapshotFile.toString();
    }

    /**
     * Load a snapshot from file.
     */
    public GraphSnapshot loadSnapshot(Path snapshotFile) throws IOException {
        return objectMapper.readValue(snapshotFile.toFile(), GraphSnapshot.class);
    }

    /**
     * Compare current graph with a previous snapshot.
     */
    public DeltaReport compareWithSnapshot(Path snapshotFile) throws IOException {
        GraphSnapshot oldSnapshot = loadSnapshot(snapshotFile);
        GraphSnapshot newSnapshot = createSnapshot();
        return compare(oldSnapshot, newSnapshot);
    }

    /**
     * Compare two snapshots.
     */
    public DeltaReport compare(GraphSnapshot oldSnapshot, GraphSnapshot newSnapshot) {
        DeltaReport report = new DeltaReport();
        report.setOldTimestamp(oldSnapshot.getTimestamp());
        report.setNewTimestamp(newSnapshot.getTimestamp());

        // Build lookup maps
        Map<String, NodeSnapshot> oldNodes = oldSnapshot.getNodes().stream()
            .collect(Collectors.toMap(NodeSnapshot::getId, n -> n));
        Map<String, NodeSnapshot> newNodes = newSnapshot.getNodes().stream()
            .collect(Collectors.toMap(NodeSnapshot::getId, n -> n));

        Set<String> oldEdgeKeys = oldSnapshot.getEdges().stream()
            .map(e -> e.getSource() + "->" + e.getTarget() + ":" + e.getType())
            .collect(Collectors.toSet());
        Set<String> newEdgeKeys = newSnapshot.getEdges().stream()
            .map(e -> e.getSource() + "->" + e.getTarget() + ":" + e.getType())
            .collect(Collectors.toSet());

        // Find added nodes
        for (NodeSnapshot node : newSnapshot.getNodes()) {
            if (!oldNodes.containsKey(node.getId())) {
                report.getAddedNodes().add(node);
            }
        }

        // Find removed nodes
        for (NodeSnapshot node : oldSnapshot.getNodes()) {
            if (!newNodes.containsKey(node.getId())) {
                report.getRemovedNodes().add(node);
            }
        }

        // Find modified nodes (type changed)
        for (NodeSnapshot newNode : newSnapshot.getNodes()) {
            NodeSnapshot oldNode = oldNodes.get(newNode.getId());
            if (oldNode != null && !oldNode.getType().equals(newNode.getType())) {
                report.getModifiedNodes().add(new ModifiedNode(oldNode, newNode));
            }
        }

        // Find added edges
        for (EdgeSnapshot edge : newSnapshot.getEdges()) {
            String key = edge.getSource() + "->" + edge.getTarget() + ":" + edge.getType();
            if (!oldEdgeKeys.contains(key)) {
                report.getAddedEdges().add(edge);
            }
        }

        // Find removed edges
        for (EdgeSnapshot edge : oldSnapshot.getEdges()) {
            String key = edge.getSource() + "->" + edge.getTarget() + ":" + edge.getType();
            if (!newEdgeKeys.contains(key)) {
                report.getRemovedEdges().add(edge);
            }
        }

        // Calculate summary stats
        report.setTotalNodesOld(oldSnapshot.getNodes().size());
        report.setTotalNodesNew(newSnapshot.getNodes().size());
        report.setTotalEdgesOld(oldSnapshot.getEdges().size());
        report.setTotalEdgesNew(newSnapshot.getEdges().size());

        // Determine severity
        if (report.getRemovedNodes().stream().anyMatch(n -> 
            n.getType().equals("OSGI_SERVICE") || n.getType().equals("SLING_MODEL"))) {
            report.setSeverity("HIGH");
        } else if (!report.getRemovedNodes().isEmpty() || !report.getRemovedEdges().isEmpty()) {
            report.setSeverity("MEDIUM");
        } else if (!report.getAddedNodes().isEmpty()) {
            report.setSeverity("LOW");
        } else {
            report.setSeverity("NONE");
        }

        log.info("Delta analysis complete: {} added nodes, {} removed nodes, {} added edges, {} removed edges",
            report.getAddedNodes().size(), report.getRemovedNodes().size(),
            report.getAddedEdges().size(), report.getRemovedEdges().size());

        return report;
    }

    /**
     * Create a snapshot of the current graph.
     */
    private GraphSnapshot createSnapshot() {
        Graph<CodeNode, Relationship> graph = graphService.getGraph();
        
        GraphSnapshot snapshot = new GraphSnapshot();
        snapshot.setTimestamp(LocalDateTime.now().toString());
        
        // Capture nodes
        for (CodeNode node : graph.vertexSet()) {
            NodeSnapshot ns = new NodeSnapshot();
            ns.setId(node.getId());
            ns.setName(node.getName());
            ns.setType(node.getType().name());
            ns.setSignature(node.getSignature());
            snapshot.getNodes().add(ns);
        }

        // Capture edges
        for (Relationship edge : graph.edgeSet()) {
            EdgeSnapshot es = new EdgeSnapshot();
            es.setSource(graph.getEdgeSource(edge).getId());
            es.setTarget(graph.getEdgeTarget(edge).getId());
            es.setType(edge.getType().name());
            snapshot.getEdges().add(es);
        }

        return snapshot;
    }

    /**
     * Generate a human-readable delta report.
     */
    public String generateTextReport(DeltaReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════════════════════════\n");
        sb.append("                    DEDE-JAVA DELTA REPORT                 \n");
        sb.append("═══════════════════════════════════════════════════════════\n\n");

        sb.append(String.format("Old snapshot: %s\n", report.getOldTimestamp()));
        sb.append(String.format("New snapshot: %s\n", report.getNewTimestamp()));
        sb.append(String.format("Severity: %s\n\n", report.getSeverity()));

        sb.append("SUMMARY\n");
        sb.append("───────────────────────────────────────────────────────────\n");
        sb.append(String.format("  Nodes: %d → %d (Δ %+d)\n", 
            report.getTotalNodesOld(), report.getTotalNodesNew(),
            report.getTotalNodesNew() - report.getTotalNodesOld()));
        sb.append(String.format("  Edges: %d → %d (Δ %+d)\n\n",
            report.getTotalEdgesOld(), report.getTotalEdgesNew(),
            report.getTotalEdgesNew() - report.getTotalEdgesOld()));

        if (!report.getAddedNodes().isEmpty()) {
            sb.append(String.format("ADDED NODES (%d)\n", report.getAddedNodes().size()));
            sb.append("───────────────────────────────────────────────────────────\n");
            for (NodeSnapshot node : report.getAddedNodes().subList(0, Math.min(10, report.getAddedNodes().size()))) {
                sb.append(String.format("  + [%s] %s\n", node.getType(), node.getName()));
            }
            if (report.getAddedNodes().size() > 10) {
                sb.append(String.format("  ... and %d more\n", report.getAddedNodes().size() - 10));
            }
            sb.append("\n");
        }

        if (!report.getRemovedNodes().isEmpty()) {
            sb.append(String.format("REMOVED NODES (%d) ⚠\n", report.getRemovedNodes().size()));
            sb.append("───────────────────────────────────────────────────────────\n");
            for (NodeSnapshot node : report.getRemovedNodes().subList(0, Math.min(10, report.getRemovedNodes().size()))) {
                sb.append(String.format("  - [%s] %s\n", node.getType(), node.getName()));
            }
            if (report.getRemovedNodes().size() > 10) {
                sb.append(String.format("  ... and %d more\n", report.getRemovedNodes().size() - 10));
            }
            sb.append("\n");
        }

        if (!report.getAddedEdges().isEmpty()) {
            sb.append(String.format("NEW DEPENDENCIES (%d)\n", report.getAddedEdges().size()));
            sb.append("───────────────────────────────────────────────────────────\n");
            for (EdgeSnapshot edge : report.getAddedEdges().subList(0, Math.min(10, report.getAddedEdges().size()))) {
                sb.append(String.format("  + %s -[%s]-> %s\n", 
                    edge.getSource().substring(edge.getSource().lastIndexOf(':') + 1),
                    edge.getType(),
                    edge.getTarget().substring(edge.getTarget().lastIndexOf(':') + 1)));
            }
            if (report.getAddedEdges().size() > 10) {
                sb.append(String.format("  ... and %d more\n", report.getAddedEdges().size() - 10));
            }
            sb.append("\n");
        }

        if (!report.getRemovedEdges().isEmpty()) {
            sb.append(String.format("REMOVED DEPENDENCIES (%d) ⚠\n", report.getRemovedEdges().size()));
            sb.append("───────────────────────────────────────────────────────────\n");
            for (EdgeSnapshot edge : report.getRemovedEdges().subList(0, Math.min(10, report.getRemovedEdges().size()))) {
                sb.append(String.format("  - %s -[%s]-> %s\n",
                    edge.getSource().substring(edge.getSource().lastIndexOf(':') + 1),
                    edge.getType(),
                    edge.getTarget().substring(edge.getTarget().lastIndexOf(':') + 1)));
            }
            if (report.getRemovedEdges().size() > 10) {
                sb.append(String.format("  ... and %d more\n", report.getRemovedEdges().size() - 10));
            }
        }

        sb.append("\n═══════════════════════════════════════════════════════════\n");
        return sb.toString();
    }

    // DTO Classes
    public static class GraphSnapshot {
        private String timestamp;
        private List<NodeSnapshot> nodes = new ArrayList<>();
        private List<EdgeSnapshot> edges = new ArrayList<>();

        public String getTimestamp() { return timestamp; }
        public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
        public List<NodeSnapshot> getNodes() { return nodes; }
        public void setNodes(List<NodeSnapshot> nodes) { this.nodes = nodes; }
        public List<EdgeSnapshot> getEdges() { return edges; }
        public void setEdges(List<EdgeSnapshot> edges) { this.edges = edges; }
    }

    public static class NodeSnapshot {
        private String id;
        private String name;
        private String type;
        private String signature;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getSignature() { return signature; }
        public void setSignature(String signature) { this.signature = signature; }
    }

    public static class EdgeSnapshot {
        private String source;
        private String target;
        private String type;

        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
        public String getTarget() { return target; }
        public void setTarget(String target) { this.target = target; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
    }

    public static class ModifiedNode {
        private NodeSnapshot oldNode;
        private NodeSnapshot newNode;

        public ModifiedNode(NodeSnapshot oldNode, NodeSnapshot newNode) {
            this.oldNode = oldNode;
            this.newNode = newNode;
        }

        public NodeSnapshot getOldNode() { return oldNode; }
        public NodeSnapshot getNewNode() { return newNode; }
    }

    public static class DeltaReport {
        private String oldTimestamp;
        private String newTimestamp;
        private String severity;
        private int totalNodesOld;
        private int totalNodesNew;
        private int totalEdgesOld;
        private int totalEdgesNew;
        private List<NodeSnapshot> addedNodes = new ArrayList<>();
        private List<NodeSnapshot> removedNodes = new ArrayList<>();
        private List<ModifiedNode> modifiedNodes = new ArrayList<>();
        private List<EdgeSnapshot> addedEdges = new ArrayList<>();
        private List<EdgeSnapshot> removedEdges = new ArrayList<>();

        public String getOldTimestamp() { return oldTimestamp; }
        public void setOldTimestamp(String oldTimestamp) { this.oldTimestamp = oldTimestamp; }
        public String getNewTimestamp() { return newTimestamp; }
        public void setNewTimestamp(String newTimestamp) { this.newTimestamp = newTimestamp; }
        public String getSeverity() { return severity; }
        public void setSeverity(String severity) { this.severity = severity; }
        public int getTotalNodesOld() { return totalNodesOld; }
        public void setTotalNodesOld(int totalNodesOld) { this.totalNodesOld = totalNodesOld; }
        public int getTotalNodesNew() { return totalNodesNew; }
        public void setTotalNodesNew(int totalNodesNew) { this.totalNodesNew = totalNodesNew; }
        public int getTotalEdgesOld() { return totalEdgesOld; }
        public void setTotalEdgesOld(int totalEdgesOld) { this.totalEdgesOld = totalEdgesOld; }
        public int getTotalEdgesNew() { return totalEdgesNew; }
        public void setTotalEdgesNew(int totalEdgesNew) { this.totalEdgesNew = totalEdgesNew; }
        public List<NodeSnapshot> getAddedNodes() { return addedNodes; }
        public List<NodeSnapshot> getRemovedNodes() { return removedNodes; }
        public List<ModifiedNode> getModifiedNodes() { return modifiedNodes; }
        public List<EdgeSnapshot> getAddedEdges() { return addedEdges; }
        public List<EdgeSnapshot> getRemovedEdges() { return removedEdges; }
    }
}
