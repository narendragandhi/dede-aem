package com.dede.runtime;

import com.dede.cloud.CloudFindings.Finding;
import com.dede.domain.GraphService;
import com.dede.domain.NodeNames;
import com.dede.domain.model.CodeNode;
import com.dede.domain.model.Relationship;
import com.dede.domain.model.RelationshipType;
import com.dede.domain.model.ResourceTypes;
import com.dede.domain.model.RuntimeMetrics;
import org.jgrapht.Graph;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The blast radius of changing one node, as data for a viewer: everything that depends on it
 * (dependency edges only, by hop), the pages that render the affected components, the traffic
 * observed on each, and the findings along the way.
 *
 * <p>The chain runs past the code: class → interface → HTL → component → pages → requests.
 * Pages come from the {@link ContentIndex} (repository content or {@code --content}); requests
 * from {@link RequestAttribution}. Every count is observed data or absent -- nothing is estimated
 * here beyond what request attribution already estimates.
 */
public final class BlastRadiusExport {

    /**
     * Edges recorded provider → consumer: the <em>target</em> depends on the source. A model is
     * INSTANTIATED_BY-linked to the resource type that renders through it; a servlet HANDLES the
     * resource type or path whose requests it serves; a bundle PROVIDES/EXPORTS what others use.
     */
    private static final Set<RelationshipType> TARGET_DEPENDS = Set.of(
        RelationshipType.INSTANTIATED_BY, RelationshipType.HANDLES_RESOURCE_TYPE, RelationshipType.HANDLES_PATH,
        RelationshipType.FILTERS_SCOPE, RelationshipType.PROVIDES, RelationshipType.EXPORTS, RelationshipType.PROCESSES);
    /**
     * Edges that are not dependencies at all: containment, CVE exposure, packaging, and
     * registration details. Every other type is recorded consumer → provider (the source depends).
     */
    private static final Set<RelationshipType> NOT_DEPENDENCIES = Set.of(
        RelationshipType.CONTAINS, RelationshipType.DECLARES, RelationshipType.EXPOSES, RelationshipType.CONFIGURES,
        RelationshipType.PACKAGES, RelationshipType.VIOLATES, RelationshipType.HANDLES_EXTENSION,
        RelationshipType.HANDLES_SELECTOR, RelationshipType.PRODUCES);
    static final int MAX_HOPS = 6;
    static final int MAX_NODES = 300;
    static final int MAX_PAGES = 200;

    private BlastRadiusExport() {}

    /**
     * @param content   the content index, or null when no content is known (no page layer)
     * @param requests  request attribution, or null when no request data was joined
     * @param findings  the scan's findings, to badge nodes
     * @param projectRoot to report files relative to the project (no absolute paths in the export)
     */
    public static Map<String, Object> build(String name, GraphService graph, ContentIndex content,
                                            RequestAttribution requests, List<Finding> findings, Path projectRoot) {
        CodeNode root = NodeNames.resolve(graph, name);
        Graph<CodeNode, Relationship> g = graph.getGraph();

        // Breadth-first over incoming dependency edges: hop 1 depends directly on the root.
        Map<CodeNode, Integer> hop = new LinkedHashMap<>();
        Map<CodeNode, CodeNode> parent = new HashMap<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        ArrayDeque<CodeNode> queue = new ArrayDeque<>(List.of(root));
        hop.put(root, 0);
        boolean truncated = false;
        Map<String, List<CodeNode>> classesBySimpleName = classesBySimpleName(graph);
        while (!queue.isEmpty()) {
            CodeNode node = queue.poll();
            int depth = hop.get(node);
            if (depth >= MAX_HOPS) continue;
            for (Dependent d : dependentsOf(g, node, classesBySimpleName)) {
                if (!hop.containsKey(d.node())) {
                    if (hop.size() >= MAX_NODES) { truncated = true; continue; }
                    hop.put(d.node(), depth + 1);
                    parent.put(d.node(), node);
                    queue.add(d.node());
                }
                if (hop.containsKey(d.node())) {
                    edges.add(Map.of("source", d.node().getId(), "target", node.getId(), "type", d.type()));
                }
            }
        }

        Map<Path, List<Finding>> findingsByFile = new HashMap<>();
        for (Finding f : findings) {
            if (f.file() != null) findingsByFile.computeIfAbsent(normalize(f.file()), p -> new ArrayList<>()).add(f);
        }

        List<Map<String, Object>> nodes = new ArrayList<>();
        int findingCount = 0;
        for (Map.Entry<CodeNode, Integer> e : hop.entrySet()) {
            CodeNode n = e.getKey();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", n.getId());
            row.put("name", displayName(n));
            row.put("type", n.getType().name());
            row.put("hop", e.getValue());
            if (parent.containsKey(n)) row.put("via", parent.get(n).getId());
            String file = relativeFile(n, projectRoot);
            if (file != null) row.put("file", file);
            Map<String, Long> traffic = traffic(n);
            if (!traffic.isEmpty()) row.put("traffic", traffic);
            List<Map<String, Object>> nodeFindings = n.getFilePath() == null || !n.getId().startsWith("class:") ? List.of()
                : findingsByFile.getOrDefault(normalize(n.getFilePath()), List.of()).stream().map(f -> {
                    Map<String, Object> fr = new LinkedHashMap<>();
                    fr.put("severity", f.severity());
                    fr.put("category", f.category());
                    fr.put("line", f.line());
                    fr.put("message", f.message());
                    return fr;
                }).toList();
            if (!nodeFindings.isEmpty()) row.put("findings", nodeFindings);
            findingCount += nodeFindings.size();
            nodes.add(row);
        }

        // Page layer: pages rendering any affected resource type, with the requests each received.
        Map<String, Map<String, Object>> pages = new LinkedHashMap<>();
        if (content != null) {
            for (CodeNode n : hop.keySet()) {
                if (!ResourceTypes.isResourceType(n)) continue;
                for (String page : content.pagesUsing(n.getName())) {
                    Map<String, Object> row = pages.computeIfAbsent(page, p -> {
                        Map<String, Object> pr = new LinkedHashMap<>();
                        pr.put("path", p);
                        pr.put("requests", requests == null ? null : requests.requestsForResource(p));
                        pr.put("via", new ArrayList<String>());
                        return pr;
                    });
                    @SuppressWarnings("unchecked") List<String> via = (List<String>) row.get("via");
                    via.add(n.getId());
                }
            }
        }
        List<Map<String, Object>> pageRows = pages.values().stream()
            .sorted(Comparator.comparingLong((Map<String, Object> p) -> p.get("requests") == null ? 0 : (Long) p.get("requests"))
                .reversed().thenComparing(p -> (String) p.get("path")))
            .toList();
        long observedRequests = pageRows.stream().mapToLong(p -> p.get("requests") == null ? 0 : (Long) p.get("requests")).sum();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tool", "dede");
        out.put("kind", "blast-radius");
        out.put("schema", 1);
        out.put("root", nodes.get(0));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("dependents", hop.size() - 1);
        summary.put("maxHop", hop.values().stream().mapToInt(Integer::intValue).max().orElse(0));
        summary.put("resourceTypes", hop.keySet().stream().filter(ResourceTypes::isResourceType).count());
        summary.put("pages", pageRows.size());
        summary.put("observedRequests", requests == null ? null : observedRequests);
        summary.put("findings", findingCount);
        summary.put("truncated", truncated);
        out.put("summary", summary);
        out.put("dataSources", Map.of(
            "content", content != null,
            "requests", requests != null));
        out.put("nodes", nodes);
        out.put("edges", edges);
        out.put("pages", pageRows.size() > MAX_PAGES ? pageRows.subList(0, MAX_PAGES) : pageRows);
        out.put("path", hottestPath(root, hop, parent, pageRows));
        out.put("notes", List.of(
            "Dependents follow dependency edges only; containment (package, bundle) is not a dependency.",
            "Requests are those that reached AEM: on publish, dispatcher and CDN cache hits are not included.",
            "Pages are those in the indexed content (repository or --content); pages authored only in AEM need --content."));
        return out;
    }

    /** A node that depends on another, and the relationship that says so. */
    record Dependent(CodeNode node, String type) {}

    /**
     * What depends on {@code node}. Most edges point consumer → provider; {@link #TARGET_DEPENDS}
     * point the other way. An implementation's callers reach it through its interfaces, so the
     * interfaces it implements count as dependents (resolved from the simple name the graph
     * records to the project's interface class when that is unambiguous).
     */
    static List<Dependent> dependentsOf(Graph<CodeNode, Relationship> g, CodeNode node,
                                        Map<String, List<CodeNode>> classesBySimpleName) {
        List<Dependent> out = new ArrayList<>();
        for (Relationship r : g.incomingEdgesOf(node)) {
            RelationshipType type = r.getType();
            CodeNode source = g.getEdgeSource(r);
            if (NOT_DEPENDENCIES.contains(type) || TARGET_DEPENDS.contains(type)) continue;
            // ADAPTS_TO from a resource type means it renders through this model; from an
            // adaptable (the request, a Resource) it only says what the model can be built from.
            if (type == RelationshipType.ADAPTS_TO && !ResourceTypes.isResourceType(source)) continue;
            out.add(new Dependent(source, type.name()));
        }
        for (Relationship r : g.outgoingEdgesOf(node)) {
            RelationshipType type = r.getType();
            CodeNode target = g.getEdgeTarget(r);
            if (TARGET_DEPENDS.contains(type)) {
                out.add(new Dependent(target, type.name()));
            } else if (type == RelationshipType.IMPLEMENTS) {
                out.add(new Dependent(resolveInterface(target, classesBySimpleName), "IMPLEMENTED_BY"));
            }
        }
        out.sort(Comparator.comparing((Dependent d) -> d.node().getId()).thenComparing(Dependent::type));
        return out;
    }

    /** {@code interface:Byline} → the project's {@code class:...Byline}, when exactly one exists. */
    private static CodeNode resolveInterface(CodeNode target, Map<String, List<CodeNode>> classesBySimpleName) {
        String id = target.getId();
        if (id.startsWith("class:")) return target;
        String simple = id.substring(id.indexOf(':') + 1);
        simple = simple.substring(simple.lastIndexOf('.') + 1);
        List<CodeNode> candidates = classesBySimpleName.getOrDefault(simple, List.of());
        return candidates.size() == 1 ? candidates.get(0) : target;
    }

    private static Map<String, List<CodeNode>> classesBySimpleName(GraphService graph) {
        Map<String, List<CodeNode>> bySimpleName = new HashMap<>();
        for (CodeNode n : graph.getAllNodes()) {
            if (!n.getId().startsWith("class:") || n.getFilePath() == null) continue;
            String fqcn = n.getId().substring("class:".length());
            bySimpleName.computeIfAbsent(fqcn.substring(fqcn.lastIndexOf('.') + 1), k -> new ArrayList<>()).add(n);
        }
        return bySimpleName;
    }

    /**
     * The route a walkthrough takes: root → … → the resource type behind the busiest page → that
     * page. Without pages, to the dependent with the most observed traffic, else the deepest one.
     */
    static List<Map<String, Object>> hottestPath(CodeNode root, Map<CodeNode, Integer> hop,
                                                 Map<CodeNode, CodeNode> parent, List<Map<String, Object>> pages) {
        CodeNode end = null;
        Map<String, Object> page = null;
        if (!pages.isEmpty()) {
            page = pages.get(0);
            @SuppressWarnings("unchecked") List<String> via = (List<String>) page.get("via");
            end = hop.keySet().stream().filter(n -> via.contains(n.getId()))
                .min(Comparator.comparingInt(hop::get)).orElse(null);
        }
        if (end == null) {
            end = hop.keySet().stream().filter(n -> n != root)
                .max(Comparator.comparingLong((CodeNode n) -> {
                    Long t = RuntimeMetrics.traffic(n);
                    return t == null ? -1 : t;
                }).thenComparingInt(hop::get).thenComparing(CodeNode::getId, Comparator.reverseOrder()))
                .orElse(root);
        }
        List<Map<String, Object>> steps = new ArrayList<>();
        for (CodeNode n = end; n != null; n = parent.get(n)) {
            steps.add(0, Map.of("kind", "node", "id", n.getId()));
        }
        if (page != null) steps.add(Map.of("kind", "page", "path", page.get("path")));
        return steps;
    }

    private static Map<String, Long> traffic(CodeNode n) {
        Map<String, Long> t = new LinkedHashMap<>();
        for (String metric : List.of(RuntimeMetrics.REQUESTS, RuntimeMetrics.RENDERS, RuntimeMetrics.CALLS,
                RuntimeMetrics.ERRORS, RuntimeMetrics.LOG_ERRORS, RuntimeMetrics.LOG_WARNINGS)) {
            Double v = RuntimeMetrics.get(n, metric);
            if (v != null) t.put(metric.substring(RuntimeMetrics.PREFIX.length()), v.longValue());
        }
        return t;
    }

    /** Short, readable names: simple class names, HTL file names; resource types and paths as-is. */
    static String displayName(CodeNode n) {
        String id = n.getId();
        if (id.startsWith("class:")) {
            String fqcn = id.substring("class:".length());
            return fqcn.substring(fqcn.lastIndexOf('.') + 1);
        }
        if (id.startsWith("htl:")) return id.substring(id.lastIndexOf('/') + 1);
        return n.getName() != null ? n.getName() : id;
    }

    private static String relativeFile(CodeNode n, Path projectRoot) {
        if (n.getFilePath() == null) return null;
        Path file = normalize(n.getFilePath());
        Path root = projectRoot.toAbsolutePath().normalize();
        return file.startsWith(root) ? root.relativize(file).toString().replace('\\', '/') : file.getFileName().toString();
    }

    private static Path normalize(String file) {
        return Path.of(file).toAbsolutePath().normalize();
    }
}
