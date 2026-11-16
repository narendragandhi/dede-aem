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
 * - Compare runtime data (calls, requests, renders, errors) when the scans joined it: what
 *   started failing, what stopped being called, what got slower
 */
@Service
public class DeltaAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(DeltaAnalyzer.class);
    // Snapshots outlive versions: newer fields in an old reader shouldn't break --compare.
    private static final ObjectMapper objectMapper = new ObjectMapper()
        .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

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

        report.getRuntimeChanges().addAll(runtimeChanges(oldSnapshot, newSnapshot, oldNodes));

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
        // Code that started failing matters as much as a removed dependency.
        boolean newErrors = report.getRuntimeChanges().stream().anyMatch(RuntimeChange::isNewErrors);
        if (newErrors && ("NONE".equals(report.getSeverity()) || "LOW".equals(report.getSeverity()))) {
            report.setSeverity("MEDIUM");
        }

        log.info("Delta analysis complete: {} added nodes, {} removed nodes, {} added edges, {} removed edges",
            report.getAddedNodes().size(), report.getRemovedNodes().size(),
            report.getAddedEdges().size(), report.getRemovedEdges().size());

        return report;
    }

    /** Runtime counters compared between snapshots (see com.dede.runtime). */
    static final List<String> COUNT_METRICS = List.of(
        "runtime.calls", "runtime.requests", "runtime.renders", "runtime.errors",
        "runtime.logErrors", "runtime.logWarnings");
    static final String LATENCY_METRIC = "runtime.avgMs";
    private static final Set<String> ERROR_METRICS = Set.of("runtime.errors", "runtime.logErrors");

    /**
     * Changes in runtime counters for nodes present in both snapshots. A metric is compared only
     * when both scans recorded it somewhere (a scan without --error-log says nothing about log
     * errors); within that, a node without the metric counts as 0. Reported: appeared (0 -> n),
     * disappeared (n -> 0), doubled or halved with a difference of at least 10, and average
     * latency up or down 50% by at least 5 ms. Counts are raw, so compare windows of similar length.
     */
    static List<RuntimeChange> runtimeChanges(GraphSnapshot oldSnapshot, GraphSnapshot newSnapshot,
                                              Map<String, NodeSnapshot> oldNodes) {
        Set<String> oldMetrics = metricsIn(oldSnapshot);
        Set<String> newMetrics = metricsIn(newSnapshot);
        List<RuntimeChange> changes = new ArrayList<>();
        for (NodeSnapshot newNode : newSnapshot.getNodes()) {
            NodeSnapshot oldNode = oldNodes.get(newNode.getId());
            if (oldNode == null) continue;
            for (String metric : COUNT_METRICS) {
                if (!oldMetrics.contains(metric) || !newMetrics.contains(metric)) continue;
                double before = value(oldNode, metric);
                double after = value(newNode, metric);
                boolean appeared = before == 0 && after > 0;
                boolean disappeared = before > 0 && after == 0;
                boolean big = Math.abs(after - before) >= 10 && (after >= 2 * before || after * 2 <= before);
                if (appeared || disappeared || big) {
                    changes.add(new RuntimeChange(newNode.getId(), newNode.getName(), metric, before, after));
                }
            }
            if (oldNode.getRuntime().containsKey(LATENCY_METRIC) && newNode.getRuntime().containsKey(LATENCY_METRIC)) {
                double before = value(oldNode, LATENCY_METRIC);
                double after = value(newNode, LATENCY_METRIC);
                if (Math.abs(after - before) >= 5 && (after >= 1.5 * before || after * 1.5 <= before)) {
                    changes.add(new RuntimeChange(newNode.getId(), newNode.getName(), LATENCY_METRIC, before, after));
                }
            }
        }
        // New errors first, then by size of the change.
        changes.sort(Comparator.comparing(RuntimeChange::isNewErrors).reversed()
            .thenComparing(Comparator.comparingDouble((RuntimeChange c) -> Math.abs(c.getNewValue() - c.getOldValue())).reversed()));
        return changes;
    }

    private static Set<String> metricsIn(GraphSnapshot snapshot) {
        Set<String> metrics = new HashSet<>();
        snapshot.getNodes().forEach(n -> metrics.addAll(n.getRuntime().keySet()));
        return metrics;
    }

    private static double value(NodeSnapshot node, String metric) {
        String raw = node.getRuntime().get(metric);
        if (raw == null) return 0;
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
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
            node.getProperties().forEach((key, value) -> {
                if (key.startsWith("runtime.")) ns.getRuntime().put(key, value);
            });
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
            sb.append("\n");
        }

        if (!report.getRuntimeChanges().isEmpty()) {
            sb.append(String.format("RUNTIME CHANGES (%d)\n", report.getRuntimeChanges().size()));
            sb.append("───────────────────────────────────────────────────────────\n");
            for (RuntimeChange c : report.getRuntimeChanges().subList(0, Math.min(10, report.getRuntimeChanges().size()))) {
                sb.append(String.format("  %s %s %s: %s → %s\n", c.isNewErrors() ? "!" : "~", c.getName(),
                    c.getMetric().substring("runtime.".length()), number(c.getOldValue()), number(c.getNewValue())));
            }
            if (report.getRuntimeChanges().size() > 10) {
                sb.append(String.format("  ... and %d more\n", report.getRuntimeChanges().size() - 10));
            }
            sb.append("  (raw counts: compare trace/log windows of similar length)\n");
        }

        sb.append("\n═══════════════════════════════════════════════════════════\n");
        return sb.toString();
    }

    private static String number(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.format(Locale.ROOT, "%.2f", v);
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
        /** runtime.* properties at snapshot time; empty when the scan joined no runtime data. */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
        private Map<String, String> runtime = new TreeMap<>();

        public Map<String, String> getRuntime() { return runtime; }
        public void setRuntime(Map<String, String> runtime) { this.runtime = runtime != null ? runtime : new TreeMap<>(); }
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

    /** One runtime counter that changed notably between snapshots. */
    public static class RuntimeChange {
        private String id;
        private String name;
        private String metric;
        private double oldValue;
        private double newValue;

        public RuntimeChange() {}

        public RuntimeChange(String id, String name, String metric, double oldValue, double newValue) {
            this.id = id;
            this.name = name;
            this.metric = metric;
            this.oldValue = oldValue;
            this.newValue = newValue;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public String getMetric() { return metric; }
        public double getOldValue() { return oldValue; }
        public double getNewValue() { return newValue; }
        /** Errors where there were none before. */
        public boolean isNewErrors() { return ERROR_METRICS.contains(metric) && oldValue == 0 && newValue > 0; }
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
        private List<RuntimeChange> runtimeChanges = new ArrayList<>();

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
        public List<RuntimeChange> getRuntimeChanges() { return runtimeChanges; }
    }
}
