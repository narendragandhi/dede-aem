package com.dede.api;

import com.dede.domain.GraphService;
import com.dede.domain.model.CodeNode;
import com.dede.domain.model.NodeType;
import com.dede.domain.model.Relationship;
import com.dede.domain.model.RelationshipType;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.util.*;
import java.util.stream.Collectors;

/**
 * GraphQL controller for powerful architectural queries.
 * Enables queries like "find all services consumed by this model" or
 * "what would be affected if I change this class?"
 */
@Controller
public class GraphQLController {

    private final GraphService graphService;

    public GraphQLController(GraphService graphService) {
        this.graphService = graphService;
    }

    @QueryMapping
    public CodeNode node(@Argument String id) {
        return graphService.findNodeById(id).orElse(null);
    }

    @QueryMapping
    public List<CodeNode> searchNodes(@Argument String name, 
                                       @Argument List<String> types,
                                       @Argument Integer limit) {
        int maxResults = limit != null ? limit : 100;
        Set<NodeType> typeFilter = types != null ? 
            types.stream().map(NodeType::valueOf).collect(Collectors.toSet()) : null;
        
        return graphService.getGraph().vertexSet().stream()
            .filter(n -> n.getName().toLowerCase().contains(name.toLowerCase()) ||
                        n.getId().toLowerCase().contains(name.toLowerCase()))
            .filter(n -> typeFilter == null || typeFilter.contains(n.getType()))
            .limit(maxResults)
            .collect(Collectors.toList());
    }

    @QueryMapping
    public List<CodeNode> nodesByType(@Argument String type, @Argument Integer limit) {
        int maxResults = limit != null ? limit : 100;
        NodeType nodeType = NodeType.valueOf(type);
        
        return graphService.getGraph().vertexSet().stream()
            .filter(n -> n.getType() == nodeType)
            .limit(maxResults)
            .collect(Collectors.toList());
    }

    @QueryMapping
    public List<Map<String, Object>> connectedNodes(@Argument String nodeId,
                                                     @Argument String direction,
                                                     @Argument List<String> relationshipTypes) {
        CodeNode node = graphService.findNodeById(nodeId).orElse(null);
        if (node == null) return List.of();

        Set<RelationshipType> typeFilter = relationshipTypes != null ?
            relationshipTypes.stream().map(RelationshipType::valueOf).collect(Collectors.toSet()) : null;

        List<Map<String, Object>> connections = new ArrayList<>();
        var graph = graphService.getGraph();

        // Outgoing edges
        if (direction == null || direction.equals("OUTGOING") || direction.equals("BOTH")) {
            graph.outgoingEdgesOf(node).stream()
                .filter(e -> typeFilter == null || typeFilter.contains(e.getType()))
                .forEach(e -> {
                    Map<String, Object> conn = new HashMap<>();
                    conn.put("source", node);
                    conn.put("target", graph.getEdgeTarget(e));
                    conn.put("type", e.getType().name());
                    conn.put("confidence", e.getConfidence());
                    connections.add(conn);
                });
        }

        // Incoming edges
        if (direction == null || direction.equals("INCOMING") || direction.equals("BOTH")) {
            graph.incomingEdgesOf(node).stream()
                .filter(e -> typeFilter == null || typeFilter.contains(e.getType()))
                .forEach(e -> {
                    Map<String, Object> conn = new HashMap<>();
                    conn.put("source", graph.getEdgeSource(e));
                    conn.put("target", node);
                    conn.put("type", e.getType().name());
                    conn.put("confidence", e.getConfidence());
                    connections.add(conn);
                });
        }

        return connections;
    }

    @QueryMapping
    public List<Map<String, Object>> findPath(@Argument String fromId, 
                                               @Argument String toId,
                                               @Argument Integer maxDepth) {
        CodeNode from = graphService.findNodeById(fromId).orElse(null);
        CodeNode to = graphService.findNodeById(toId).orElse(null);
        if (from == null || to == null) return null;

        int depth = maxDepth != null ? maxDepth : 10;
        List<CodeNode> path = bfs(from, to, depth);
        
        if (path == null) return null;

        List<Map<String, Object>> steps = new ArrayList<>();
        for (int i = 0; i < path.size(); i++) {
            Map<String, Object> step = new HashMap<>();
            step.put("node", path.get(i));
            if (i < path.size() - 1) {
                var edge = graphService.getGraph().getEdge(path.get(i), path.get(i + 1));
                if (edge != null) {
                    step.put("edge", edge.getType().name());
                }
            }
            steps.add(step);
        }
        return steps;
    }

    private List<CodeNode> bfs(CodeNode from, CodeNode to, int maxDepth) {
        var graph = graphService.getGraph();
        Queue<List<CodeNode>> queue = new LinkedList<>();
        Set<CodeNode> visited = new HashSet<>();
        
        queue.add(List.of(from));
        visited.add(from);

        while (!queue.isEmpty()) {
            List<CodeNode> path = queue.poll();
            if (path.size() > maxDepth) continue;

            CodeNode current = path.get(path.size() - 1);
            if (current.equals(to)) return path;

            for (Relationship edge : graph.outgoingEdgesOf(current)) {
                CodeNode next = graph.getEdgeTarget(edge);
                if (!visited.contains(next)) {
                    visited.add(next);
                    List<CodeNode> newPath = new ArrayList<>(path);
                    newPath.add(next);
                    queue.add(newPath);
                }
            }
        }
        return null;
    }

    @QueryMapping
    public Map<String, Object> dependencyTree(@Argument String nodeId, @Argument Integer maxDepth) {
        CodeNode node = graphService.findNodeById(nodeId).orElse(null);
        if (node == null) return null;

        int depth = maxDepth != null ? maxDepth : 3;
        return buildDependencyTree(node, depth, 0, new HashSet<>());
    }

    private Map<String, Object> buildDependencyTree(CodeNode node, int maxDepth, int currentDepth, Set<String> visited) {
        Map<String, Object> result = new HashMap<>();
        result.put("node", node);
        result.put("depth", currentDepth);

        if (currentDepth >= maxDepth || visited.contains(node.getId())) {
            result.put("dependents", List.of());
            return result;
        }

        visited.add(node.getId());
        var graph = graphService.getGraph();
        
        List<Map<String, Object>> dependents = graph.incomingEdgesOf(node).stream()
            .map(e -> graph.getEdgeSource(e))
            .filter(n -> !visited.contains(n.getId()))
            .map(n -> buildDependencyTree(n, maxDepth, currentDepth + 1, new HashSet<>(visited)))
            .collect(Collectors.toList());

        result.put("dependents", dependents);
        return result;
    }

    @QueryMapping
    public Map<String, Object> impactAnalysis(@Argument String nodeId, @Argument Integer maxDepth) {
        CodeNode node = graphService.findNodeById(nodeId).orElse(null);
        if (node == null) return null;

        int depth = maxDepth != null ? maxDepth : 3;
        var graph = graphService.getGraph();

        // Direct impact - nodes directly depending on this
        Set<CodeNode> directImpact = graph.incomingEdgesOf(node).stream()
            .map(graph::getEdgeSource)
            .collect(Collectors.toSet());

        // Transitive impact - BFS to find all affected nodes
        Set<CodeNode> transitiveImpact = new HashSet<>();
        Queue<CodeNode> queue = new LinkedList<>(directImpact);
        int currentDepth = 0;

        while (!queue.isEmpty() && currentDepth < depth) {
            int levelSize = queue.size();
            for (int i = 0; i < levelSize; i++) {
                CodeNode current = queue.poll();
                if (!transitiveImpact.contains(current) && !current.equals(node)) {
                    transitiveImpact.add(current);
                    graph.incomingEdgesOf(current).stream()
                        .map(graph::getEdgeSource)
                        .filter(n -> !transitiveImpact.contains(n) && !n.equals(node))
                        .forEach(queue::add);
                }
            }
            currentDepth++;
        }

        // Filter by type
        List<CodeNode> impactedServices = transitiveImpact.stream()
            .filter(n -> n.getType() == NodeType.OSGI_SERVICE || n.getType() == NodeType.OSGI_COMPONENT)
            .collect(Collectors.toList());

        List<CodeNode> impactedHTL = transitiveImpact.stream()
            .filter(n -> n.getType() == NodeType.HTL_FILE)
            .collect(Collectors.toList());

        // Determine risk level
        String riskLevel;
        int totalImpact = transitiveImpact.size();
        if (totalImpact > 50 || impactedServices.size() > 10) {
            riskLevel = "CRITICAL";
        } else if (totalImpact > 20 || impactedServices.size() > 5) {
            riskLevel = "HIGH";
        } else if (totalImpact > 5) {
            riskLevel = "MEDIUM";
        } else {
            riskLevel = "LOW";
        }

        Map<String, Object> report = new HashMap<>();
        report.put("node", node);
        report.put("directImpact", new ArrayList<>(directImpact));
        report.put("transitiveImpact", new ArrayList<>(transitiveImpact));
        report.put("impactedServices", impactedServices);
        report.put("impactedHTL", impactedHTL);
        report.put("riskLevel", riskLevel);
        report.put("summary", String.format(
            "Change to %s would directly affect %d nodes and transitively affect %d nodes (%d services, %d HTL files). Risk: %s",
            node.getName(), directImpact.size(), transitiveImpact.size(), 
            impactedServices.size(), impactedHTL.size(), riskLevel));

        return report;
    }

    @QueryMapping
    public Map<String, Object> stats() {
        var graph = graphService.getGraph();
        
        // Count nodes by type
        Map<NodeType, Long> nodesByType = graph.vertexSet().stream()
            .collect(Collectors.groupingBy(CodeNode::getType, Collectors.counting()));

        // Count edges by type
        Map<RelationshipType, Long> edgesByType = graph.edgeSet().stream()
            .collect(Collectors.groupingBy(Relationship::getType, Collectors.counting()));

        // Find most connected nodes
        List<CodeNode> mostConnected = graph.vertexSet().stream()
            .sorted((a, b) -> Integer.compare(
                graph.inDegreeOf(b) + graph.outDegreeOf(b),
                graph.inDegreeOf(a) + graph.outDegreeOf(a)))
            .limit(10)
            .collect(Collectors.toList());

        Map<String, Object> stats = new HashMap<>();
        stats.put("nodeCount", graph.vertexSet().size());
        stats.put("edgeCount", graph.edgeSet().size());
        stats.put("nodesByType", nodesByType.entrySet().stream()
            .map(e -> Map.of("type", e.getKey().name(), "count", e.getValue()))
            .collect(Collectors.toList()));
        stats.put("edgesByType", edgesByType.entrySet().stream()
            .map(e -> Map.of("type", e.getKey().name(), "count", e.getValue()))
            .collect(Collectors.toList()));
        stats.put("mostConnected", mostConnected);

        return stats;
    }

    @QueryMapping
    public List<List<CodeNode>> findCycles(@Argument Integer maxLength) {
        return graphService.findCycles().stream()
            .filter(cycle -> maxLength == null || cycle.size() <= maxLength)
            .map(ArrayList::new)
            .collect(Collectors.toList());
    }

    @QueryMapping
    public Map<String, Object> serviceConsumption(@Argument String nodeId) {
        CodeNode node = graphService.findNodeById(nodeId).orElse(null);
        if (node == null) return null;

        var graph = graphService.getGraph();

        List<CodeNode> consumedServices = graph.outgoingEdgesOf(node).stream()
            .filter(e -> e.getType() == RelationshipType.CONSUMES || e.getType() == RelationshipType.DYNAMIC_CONSUMES)
            .map(graph::getEdgeTarget)
            .collect(Collectors.toList());

        List<CodeNode> providedServices = graph.outgoingEdgesOf(node).stream()
            .filter(e -> e.getType() == RelationshipType.PROVIDES)
            .map(graph::getEdgeTarget)
            .collect(Collectors.toList());

        List<CodeNode> dynamicDeps = graph.outgoingEdgesOf(node).stream()
            .filter(e -> e.getType() == RelationshipType.DYNAMIC_ADAPTS_TO || e.getType() == RelationshipType.DYNAMIC_CONSUMES)
            .map(graph::getEdgeTarget)
            .collect(Collectors.toList());

        Map<String, Object> report = new HashMap<>();
        report.put("node", node);
        report.put("consumedServices", consumedServices);
        report.put("providedServices", providedServices);
        report.put("dynamicDependencies", dynamicDeps);

        return report;
    }

    @QueryMapping
    public Map<String, Object> resourceTypeUsage(@Argument String resourceType) {
        var graph = graphService.getGraph();

        // Find the resource type node
        CodeNode resTypeNode = graph.vertexSet().stream()
            .filter(n -> n.getType() == NodeType.JCR_RESOURCE_TYPE)
            .filter(n -> n.getName().equals(resourceType) || n.getId().contains(resourceType))
            .findFirst()
            .orElse(null);

        List<CodeNode> models = new ArrayList<>();
        List<CodeNode> servlets = new ArrayList<>();
        List<CodeNode> htlFiles = new ArrayList<>();

        if (resTypeNode != null) {
            graph.incomingEdgesOf(resTypeNode).forEach(e -> {
                CodeNode source = graph.getEdgeSource(e);
                switch (source.getType()) {
                    case SLING_MODEL -> models.add(source);
                    case SLING_SERVLET -> servlets.add(source);
                    case HTL_FILE -> htlFiles.add(source);
                }
            });
        }

        Map<String, Object> report = new HashMap<>();
        report.put("resourceType", resourceType);
        report.put("models", models);
        report.put("servlets", servlets);
        report.put("htlFiles", htlFiles);

        return report;
    }

    // Schema mappings for nested fields on CodeNode
    @SchemaMapping(typeName = "CodeNode")
    public List<Map<String, Object>> outgoing(CodeNode node, @Argument List<String> types) {
        Set<RelationshipType> typeFilter = types != null ?
            types.stream().map(RelationshipType::valueOf).collect(Collectors.toSet()) : null;

        var graph = graphService.getGraph();
        return graph.outgoingEdgesOf(node).stream()
            .filter(e -> typeFilter == null || typeFilter.contains(e.getType()))
            .map(e -> {
                Map<String, Object> conn = new HashMap<>();
                conn.put("source", node);
                conn.put("target", graph.getEdgeTarget(e));
                conn.put("type", e.getType().name());
                conn.put("confidence", e.getConfidence());
                return conn;
            })
            .collect(Collectors.toList());
    }

    @SchemaMapping(typeName = "CodeNode")
    public List<Map<String, Object>> incoming(CodeNode node, @Argument List<String> types) {
        Set<RelationshipType> typeFilter = types != null ?
            types.stream().map(RelationshipType::valueOf).collect(Collectors.toSet()) : null;

        var graph = graphService.getGraph();
        return graph.incomingEdgesOf(node).stream()
            .filter(e -> typeFilter == null || typeFilter.contains(e.getType()))
            .map(e -> {
                Map<String, Object> conn = new HashMap<>();
                conn.put("source", graph.getEdgeSource(e));
                conn.put("target", node);
                conn.put("type", e.getType().name());
                conn.put("confidence", e.getConfidence());
                return conn;
            })
            .collect(Collectors.toList());
    }

    @SchemaMapping(typeName = "CodeNode")
    public int inDegree(CodeNode node) {
        return graphService.getGraph().inDegreeOf(node);
    }

    @SchemaMapping(typeName = "CodeNode")
    public int outDegree(CodeNode node) {
        return graphService.getGraph().outDegreeOf(node);
    }

    @SchemaMapping(typeName = "CodeNode")
    public List<Map<String, String>> properties(CodeNode node) {
        return node.getProperties().entrySet().stream()
            .map(e -> Map.of("key", e.getKey(), "value", e.getValue()))
            .collect(Collectors.toList());
    }
}
