package com.dede.runtime;

import com.dede.cloud.CloudFindings.Finding;
import com.dede.domain.GraphService;
import com.dede.domain.model.CodeNode;
import com.dede.domain.model.NodeType;
import com.dede.domain.model.RelationshipType;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Chooses which project classes the OpenTelemetry Java agent should create spans for, and
 * writes that as an agent configuration file.
 *
 * <p>The agent instruments HTTP, JDBC and HTTP clients out of the box but not project code
 * (Sling Models, OSGi services). Instrumenting every method is too expensive, so the graph
 * picks: classes with findings first (so traces show whether flagged code actually runs),
 * then the classes the most other code depends on. Traces collected with this config are what
 * {@code --runtime-traces} reads back.
 */
public final class OtelConfigEmitter {

    public static final String REASON_NO_REFERENCES = "no static references (dead-code suspect)";

    /** Edges that express structure, not dependency; they don't count toward fan-in. */
    private static final Set<RelationshipType> STRUCTURAL = Set.of(RelationshipType.CONTAINS, RelationshipType.DECLARES);

    public record Selection(String className, Set<String> methods, String reason) {}

    private OtelConfigEmitter() {}

    /** Node types AEM itself invokes: requests, model adaptation, OSGi lifecycle, jobs, workflows. */
    private static final Set<NodeType> ENTRY_POINT_TYPES = Set.of(NodeType.SLING_SERVLET, NodeType.SLING_FILTER,
        NodeType.SLING_MODEL, NodeType.OSGI_COMPONENT, NodeType.WORKFLOW_PROCESS);
    private static final Set<RelationshipType> ENTRY_POINT_EDGES = Set.of(RelationshipType.HANDLES_RESOURCE_TYPE,
        RelationshipType.HANDLES_PATH, RelationshipType.FILTERS_SCOPE, RelationshipType.PROCESSES);

    /**
     * Picks up to {@code maxClasses} project classes, in priority order:
     * <ol>
     *   <li>classes with (non-INFO) findings -- do they run, and how often?</li>
     *   <li>AEM entry points (servlets, filters, Sling Models, OSGi components, job and
     *       workflow processors) -- where requests and background work enter project code;</li>
     *   <li>other classes other code depends on, by fan-in;</li>
     *   <li>classes with no static references at all -- dead-code suspects. Instrumenting them
     *       is what turns "no calls observed" into evidence (see {@code --otel-config}).</li>
     * </ol>
     * Within a tier, higher fan-in first. Interfaces and classes without declared methods are skipped.
     */
    public static List<Selection> select(GraphService graph, List<Finding> findings, int maxClasses) {
        Set<Path> findingFiles = new HashSet<>();
        for (Finding f : findings) {
            if (f.file() != null && !"INFO".equals(f.severity())) {
                findingFiles.add(Path.of(f.file()).toAbsolutePath().normalize());
            }
        }

        List<CodeNode> candidates = new ArrayList<>();
        Map<CodeNode, Integer> fanIn = new java.util.HashMap<>();
        for (CodeNode node : graph.getAllNodes()) {
            if (node.getId().startsWith("class:") && node.getFilePath() != null && node.getType() != NodeType.INTERFACE) {
                candidates.add(node);
                fanIn.put(node, fanIn(graph, node));
            }
        }
        candidates.sort(Comparator.comparingInt((CodeNode n) -> fanIn.get(n)).reversed()
            .thenComparing(CodeNode::getSignature));

        Map<String, Selection> selected = new LinkedHashMap<>();
        for (CodeNode n : candidates) {
            if (findingFiles.contains(Path.of(n.getFilePath()).toAbsolutePath().normalize())) {
                add(selected, graph, n, "has findings", maxClasses);
            }
        }
        for (CodeNode n : candidates) {
            if (isEntryPoint(graph, n)) {
                add(selected, graph, n, "entry point (" + n.getType().name().toLowerCase(java.util.Locale.ROOT) + ")", maxClasses);
            }
        }
        for (CodeNode n : candidates) {
            if (fanIn.get(n) > 0) {
                add(selected, graph, n, "fan-in " + fanIn.get(n), maxClasses);
            }
        }
        for (CodeNode n : candidates) {
            if (fanIn.get(n) == 0 && !isEntryPoint(graph, n)) {
                add(selected, graph, n, REASON_NO_REFERENCES, maxClasses);
            }
        }
        return new ArrayList<>(selected.values());
    }

    static boolean isEntryPoint(GraphService graph, CodeNode node) {
        if (ENTRY_POINT_TYPES.contains(node.getType())) return true;
        for (var edge : graph.getGraph().outgoingEdgesOf(node)) {
            if (ENTRY_POINT_EDGES.contains(edge.getType())) return true;
        }
        return false;
    }

    private static void add(Map<String, Selection> selected, GraphService graph, CodeNode node, String reason, int max) {
        if (selected.size() >= max || selected.containsKey(node.getSignature())) return;
        Set<String> methods = new TreeSet<>();
        for (CodeNode declared : graph.getRelatedNodes(node, RelationshipType.DECLARES)) {
            if (declared.getType() == NodeType.METHOD && declared.getFilePath() != null) {
                methods.add(declared.getName());
            }
        }
        if (!methods.isEmpty()) {
            selected.put(node.getSignature(), new Selection(node.getSignature(), methods, reason));
        }
    }

    /** Non-structural edges into the class or into any method it declares. */
    static int fanIn(GraphService graph, CodeNode node) {
        int count = countDependents(graph, node);
        for (CodeNode method : graph.getRelatedNodes(node, RelationshipType.DECLARES)) {
            count += countDependents(graph, method);
        }
        return count;
    }

    private static int countDependents(GraphService graph, CodeNode node) {
        int count = 0;
        for (var edge : graph.getGraph().incomingEdgesOf(node)) {
            if (!STRUCTURAL.contains(edge.getType())) count++;
        }
        return count;
    }

    /** The {@code otel.instrumentation.methods.include} value: {@code a.B[m1,m2];c.D[m3]}. */
    public static String methodsInclude(List<Selection> selections) {
        StringBuilder value = new StringBuilder();
        for (Selection s : selections) {
            if (!value.isEmpty()) value.append(';');
            value.append(s.className()).append('[').append(String.join(",", s.methods())).append(']');
        }
        return value.toString();
    }

    /**
     * Class names a generated (or hand-written) agent config instruments, read from its
     * {@code otel.instrumentation.methods.include}. Lets the overlay tell "instrumented and
     * never called" apart from "not instrumented".
     */
    public static Set<String> readInstrumentedClasses(Path configFile) throws java.io.IOException {
        java.util.Properties props = new java.util.Properties();
        try (var in = java.nio.file.Files.newBufferedReader(configFile)) {
            props.load(in);
        }
        String include = props.getProperty("otel.instrumentation.methods.include", "");
        Set<String> classes = new java.util.LinkedHashSet<>();
        for (String entry : include.split(";")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;
            int bracket = trimmed.indexOf('[');
            classes.add(bracket > 0 ? trimmed.substring(0, bracket).trim() : trimmed);
        }
        if (classes.isEmpty()) {
            throw new java.io.IOException("no otel.instrumentation.methods.include entries in " + configFile);
        }
        return classes;
    }

    /** A complete agent configuration file ({@code -Dotel.javaagent.configuration-file=...}). */
    public static String render(List<Selection> selections) {
        StringBuilder out = new StringBuilder();
        out.append("# OpenTelemetry Java agent configuration generated by dede.\n");
        out.append("# Use: -javaagent:opentelemetry-javaagent.jar -Dotel.javaagent.configuration-file=<this file>\n");
        out.append("#\n");
        out.append("# AEM runs on OSGi: bundles cannot see the agent's classes unless they are boot-delegated.\n");
        out.append("# Add io.opentelemetry.* to org.osgi.framework.bootdelegation (crx-quickstart/conf/sling.properties),\n");
        out.append("# or project spans will be missing.\n");
        out.append("#\n");
        out.append("# Collect with an OpenTelemetry Collector 'file' exporter and feed the result back with\n");
        out.append("#   dede <project> --runtime-traces <traces.json> --json <report.json>\n");
        out.append("#\n");
        out.append("# Selected classes (").append(selections.size()).append("):\n");
        for (Selection s : selections) {
            out.append("#   ").append(s.className()).append(" -- ").append(s.reason()).append('\n');
        }
        out.append("\notel.service.name=aem\n");
        out.append("otel.instrumentation.methods.include=").append(methodsInclude(selections)).append('\n');
        return out.toString();
    }
}
