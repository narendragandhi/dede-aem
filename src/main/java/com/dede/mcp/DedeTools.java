package com.dede.mcp;

import com.dede.cloud.CloudFindings;
import com.dede.domain.GraphService;
import com.dede.domain.model.CodeNode;
import com.dede.domain.model.Relationship;
import com.dede.domain.model.RelationshipType;
import com.fasterxml.jackson.databind.JsonNode;
import org.jgrapht.Graph;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The MCP tools over one finished scan: the graph, the findings and the JSON report (with
 * whatever runtime data was joined). Read-only -- the scan is not re-run.
 */
public final class DedeTools {

    private static final int MAX_LIMIT = 500;
    /** Containment edges say where code lives, not what it depends on. */
    private static final Set<RelationshipType> STRUCTURAL = Set.of(RelationshipType.CONTAINS, RelationshipType.DECLARES);

    private final GraphService graph;
    private final Map<String, Object> report;

    /**
     * @param report the JSON report of this scan (as written by {@code --json}); its
     *               {@code violations} rows carry {@code runtimeCalls}/{@code logEntries}
     */
    public DedeTools(GraphService graph, Map<String, Object> report) {
        this.graph = graph;
        this.report = report;
    }

    public List<McpServer.Tool> tools() {
        return List.of(
            new McpServer.Tool("summary",
                "Overview of the scan: finding counts by severity, graph size, and the runtime sections that were "
                    + "joined (runtime traces, requests, error log, vulnerabilities) with their top entries.",
                schema(Map.of(), List.of()), args -> summary()),
            new McpServer.Tool("list_findings",
                "AEM as a Cloud Service readiness findings, most severe first. Each carries runtimeCalls (observed "
                    + "calls to its code) and logEntries (WARN/ERROR log entries from its code) when that data was joined.",
                schema(Map.of(
                    "min_severity", prop("string", "Lowest severity to include: CRITICAL, HIGH, MEDIUM, LOW or INFO (default INFO)"),
                    "category", prop("string", "Only this category, e.g. ADMIN_SESSION"),
                    "file_contains", prop("string", "Only findings whose file path contains this text"),
                    "runtime_only", prop("boolean", "Only findings in code that ran or logged errors"),
                    "limit", prop("integer", "Maximum rows (default 50)")), List.of()),
                this::listFindings),
            new McpServer.Tool("class_info",
                "One class (or any graph node): type, file, properties including runtime.* metrics, findings in its "
                    + "file, and its dependencies and dependents by relationship type.",
                schema(Map.of("name", prop("string", "Fully qualified or simple class name, or a node id")), List.of("name")),
                this::classInfo),
            new McpServer.Tool("impact",
                "What depends on a class, directly and transitively: the blast radius of changing or removing it.",
                schema(Map.of(
                    "name", prop("string", "Fully qualified or simple class name, or a node id"),
                    "limit", prop("integer", "Maximum transitive dependents to list (default 100)")), List.of("name")),
                this::impact),
            new McpServer.Tool("search",
                "Find graph nodes whose id or name contains the query (case-insensitive): classes, servlets, Sling "
                    + "Models, OSGi components, resource types, content paths.",
                schema(Map.of(
                    "query", prop("string", "Text to look for"),
                    "type", prop("string", "Only this node type, e.g. SLING_MODEL, SLING_SERVLET, JCR_RESOURCE_TYPE"),
                    "limit", prop("integer", "Maximum rows (default 50)")), List.of("query")),
                this::search));
    }

    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new TreeMap<>(properties));
        if (!required.isEmpty()) schema.put("required", required);
        return schema;
    }

    private static Map<String, Object> prop(String type, String description) {
        return Map.of("type", type, "description", description);
    }

    private Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", report.get("project"));
        out.put("findings", report.get("summary"));
        out.put("graph", report.get("graph"));
        List<String> joined = new ArrayList<>();
        for (String section : List.of("runtime", "requests", "logs", "vulnerabilities")) {
            if (report.containsKey(section)) {
                joined.add(section);
                out.put(section, report.get(section));
            }
        }
        out.put("runtimeSectionsJoined", joined);
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> violations() {
        Object rows = report.get("violations");
        if (rows instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map) {
            return (List<Map<String, Object>>) rows;
        }
        // Without runtime data the report holds Finding records; view them as maps.
        List<Map<String, Object>> out = new ArrayList<>();
        if (rows instanceof List<?> list) {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            for (Object f : list) out.add(mapper.convertValue(f, LinkedHashMap.class));
        }
        return out;
    }

    private Object listFindings(JsonNode args) {
        String threshold;
        try {
            threshold = CloudFindings.parseThreshold(text(args, "min_severity", "INFO"));
        } catch (IllegalArgumentException e) {
            throw new McpServer.ToolError(e.getMessage());
        }
        int maxRank = CloudFindings.SEVERITY_ORDER.indexOf(threshold);
        String category = text(args, "category", null);
        String fileContains = text(args, "file_contains", null);
        boolean runtimeOnly = args.path("runtime_only").asBoolean(false);
        int limit = limit(args, 50);

        List<Map<String, Object>> matching = violations().stream()
            .filter(v -> {
                int rank = CloudFindings.SEVERITY_ORDER.indexOf(String.valueOf(v.get("severity")));
                return rank >= 0 && rank <= maxRank;
            })
            .filter(v -> category == null || category.equalsIgnoreCase(String.valueOf(v.get("category"))))
            .filter(v -> fileContains == null || String.valueOf(v.get("file")).contains(fileContains))
            .filter(v -> !runtimeOnly || v.containsKey("runtimeCalls") || v.containsKey("logEntries"))
            .sorted(Comparator.comparingInt((Map<String, Object> v) ->
                    CloudFindings.SEVERITY_ORDER.indexOf(String.valueOf(v.get("severity"))))
                .thenComparing(Comparator.comparingLong((Map<String, Object> v) -> number(v.get("runtimeCalls"))).reversed()))
            .toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", matching.size());
        out.put("findings", matching.stream().limit(limit).toList());
        return out;
    }

    private Object classInfo(JsonNode args) {
        CodeNode node = resolve(required(args, "name"));
        Graph<CodeNode, Relationship> g = graph.getGraph();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", node.getId());
        out.put("name", node.getName());
        out.put("type", node.getType());
        out.put("signature", node.getSignature());
        out.put("file", node.getFilePath());
        out.put("properties", new TreeMap<>(node.getProperties()));
        if (node.getFilePath() != null) {
            Path file = Path.of(node.getFilePath()).toAbsolutePath().normalize();
            out.put("findings", violations().stream().filter(v -> v.get("file") != null
                && Path.of(String.valueOf(v.get("file"))).toAbsolutePath().normalize().equals(file)).toList());
        }
        Map<String, List<String>> dependsOn = new TreeMap<>();
        for (Relationship r : g.outgoingEdgesOf(node)) {
            dependsOn.computeIfAbsent(r.getType().name(), k -> new ArrayList<>()).add(g.getEdgeTarget(r).getId());
        }
        Map<String, List<String>> dependents = new TreeMap<>();
        for (Relationship r : g.incomingEdgesOf(node)) {
            dependents.computeIfAbsent(r.getType().name(), k -> new ArrayList<>()).add(g.getEdgeSource(r).getId());
        }
        out.put("dependsOn", cap(dependsOn, 50));
        out.put("dependents", cap(dependents, 50));
        return out;
    }

    private Object impact(JsonNode args) {
        CodeNode node = resolve(required(args, "name"));
        int limit = limit(args, 100);
        Graph<CodeNode, Relationship> g = graph.getGraph();
        List<String> direct = g.incomingEdgesOf(node).stream()
            .filter(r -> !STRUCTURAL.contains(r.getType()))
            .map(r -> g.getEdgeSource(r).getId()).distinct().sorted().toList();
        Set<CodeNode> transitive = graph.getTransitiveIncomingNodes(node);
        Map<String, Long> byType = new TreeMap<>();
        transitive.forEach(n -> byType.merge(n.getType().name(), 1L, Long::sum));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("node", node.getId());
        out.put("directDependents", direct);
        out.put("transitiveDependents", transitive.size());
        out.put("transitiveByType", byType);
        // Runtime-observed dependents first: those are the ones a change would visibly break.
        out.put("transitive", transitive.stream()
            .sorted(Comparator.comparingLong((CodeNode n) -> traffic(n)).reversed().thenComparing(CodeNode::getId))
            .limit(limit)
            .map(n -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", n.getId());
                row.put("type", n.getType());
                long traffic = traffic(n);
                if (traffic > 0) row.put("observedTraffic", traffic);
                return row;
            }).toList());
        return out;
    }

    private Object search(JsonNode args) {
        String query = required(args, "query").toLowerCase(Locale.ROOT);
        String type = text(args, "type", null);
        int limit = limit(args, 50);
        List<CodeNode> hits = graph.getAllNodes().stream()
            .filter(n -> type == null || n.getType().name().equalsIgnoreCase(type))
            .filter(n -> n.getId().toLowerCase(Locale.ROOT).contains(query)
                || (n.getName() != null && n.getName().toLowerCase(Locale.ROOT).contains(query)))
            .sorted(Comparator.comparing(CodeNode::getId))
            .toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", hits.size());
        out.put("nodes", hits.stream().limit(limit).map(n -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", n.getId());
            row.put("type", n.getType());
            if (n.getFilePath() != null) row.put("file", n.getFilePath());
            return row;
        }).toList());
        return out;
    }

    /** A node by id, by fully qualified class name, or by simple class name when unambiguous. */
    CodeNode resolve(String name) {
        var exact = graph.findNodeById(name).or(() -> graph.findNodeById("class:" + name));
        if (exact.isPresent()) return exact.get();
        List<CodeNode> bySimpleName = graph.getAllNodes().stream()
            .filter(n -> n.getId().startsWith("class:") && (n.getId().endsWith("." + name) || n.getId().equals("class:" + name)))
            .sorted(Comparator.comparing(CodeNode::getId))
            .toList();
        if (bySimpleName.size() == 1) return bySimpleName.get(0);
        if (bySimpleName.isEmpty()) {
            throw new McpServer.ToolError("No node or class named '" + name + "'. Try the search tool.");
        }
        throw new McpServer.ToolError("'" + name + "' is ambiguous: "
            + bySimpleName.stream().map(CodeNode::getId).toList() + ". Use the fully qualified name.");
    }

    private static long traffic(CodeNode n) {
        Long traffic = com.dede.domain.model.RuntimeMetrics.traffic(n);
        return traffic == null ? 0 : traffic;
    }

    private static long number(Object raw) {
        if (raw instanceof Number n) return n.longValue();
        if (raw == null) return 0;
        try {
            return Long.parseLong(raw.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Map<String, Object> cap(Map<String, List<String>> byType, int perType) {
        Map<String, Object> out = new TreeMap<>();
        byType.forEach((type, ids) -> out.put(type, ids.size() <= perType ? ids
            : Map.of("count", ids.size(), "first", ids.subList(0, perType))));
        return out;
    }

    private static String text(JsonNode args, String field, String fallback) {
        JsonNode v = args.get(field);
        return v == null || v.isNull() || v.asText().isBlank() ? fallback : v.asText();
    }

    private static String required(JsonNode args, String field) {
        String v = text(args, field, null);
        if (v == null) throw new McpServer.ToolError("Missing required argument '" + field + "'.");
        return v;
    }

    private static int limit(JsonNode args, int fallback) {
        int v = args.path("limit").asInt(fallback);
        return Math.max(1, Math.min(v, MAX_LIMIT));
    }
}
