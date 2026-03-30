package com.dede.aem.services.impl;

import com.dede.aem.models.CodeNode;
import com.dede.aem.models.NodeType;
import com.dede.aem.models.Relationship;
import com.dede.aem.models.RelationshipType;
import com.dede.aem.services.DedeGraphService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jgrapht.Graph;
import org.jgrapht.alg.cycle.TarjanSimpleCycles;
import org.jgrapht.graph.DirectedMultigraph;
import org.jgrapht.traverse.BreadthFirstIterator;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.RepositoryException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * OSGi implementation of DedeGraphService.
 * Stores an in-memory graph and can scan JCR content.
 */
@Component(
    service = DedeGraphService.class,
    immediate = true
)
@Designate(ocd = DedeGraphServiceImpl.Config.class)
public class DedeGraphServiceImpl implements DedeGraphService {

    private static final Logger LOG = LoggerFactory.getLogger(DedeGraphServiceImpl.class);

    @ObjectClassDefinition(name = "Dede Graph Service Configuration")
    public @interface Config {
        @AttributeDefinition(name = "Scan Paths", description = "JCR paths to scan for components")
        String[] scanPaths() default {"/apps", "/conf"};

        @AttributeDefinition(name = "Max Depth", description = "Maximum depth for JCR traversal")
        int maxDepth() default 10;

        @AttributeDefinition(name = "Excluded Paths", description = "Paths to exclude from scanning")
        String[] excludedPaths() default {"/apps/cq", "/apps/dam", "/apps/wcm"};
    }

    @Reference
    private ResourceResolverFactory resolverFactory;

    private final Graph<CodeNode, Relationship> graph;
    private final ObjectMapper objectMapper;
    private Config config;

    public DedeGraphServiceImpl() {
        this.graph = new DirectedMultigraph<>(Relationship.class);
        this.objectMapper = new ObjectMapper();
    }

    @Activate
    protected void activate(Config config) {
        this.config = config;
        LOG.info("Dede Graph Service activated. Scan paths: {}", Arrays.toString(config.scanPaths()));
    }

    @Override
    public synchronized void addNode(CodeNode node) {
        if (!graph.containsVertex(node)) {
            graph.addVertex(node);
        }
    }

    @Override
    public Relationship addEdge(CodeNode source, CodeNode target, RelationshipType type) {
        return addEdge(source, target, type, 100);
    }

    @Override
    public synchronized Relationship addEdge(CodeNode source, CodeNode target, RelationshipType type, int confidence) {
        // Prevent self-loops
        if (source.equals(target) || source.getId().equals(target.getId())) {
            return null;
        }

        addNode(source);
        addNode(target);

        // Check for existing edge of same type
        Set<Relationship> existing = graph.getAllEdges(source, target);
        for (Relationship edge : existing) {
            if (edge.getType() == type) {
                return edge;
            }
        }

        Relationship newEdge = new Relationship(type, confidence);
        graph.addEdge(source, target, newEdge);
        return newEdge;
    }

    @Override
    public Optional<CodeNode> findNodeById(String id) {
        return graph.vertexSet().stream()
            .filter(node -> node.getId().equals(id))
            .findFirst();
    }

    @Override
    public List<CodeNode> findNodesByType(NodeType type) {
        return graph.vertexSet().stream()
            .filter(node -> node.getType() == type)
            .collect(Collectors.toList());
    }

    @Override
    public Set<CodeNode> getAllNodes() {
        return Collections.unmodifiableSet(graph.vertexSet());
    }

    @Override
    public int getNodeCount() {
        return graph.vertexSet().size();
    }

    @Override
    public int getEdgeCount() {
        return graph.edgeSet().size();
    }

    @Override
    public Set<CodeNode> getIncomingNodes(CodeNode node) {
        if (!graph.containsVertex(node)) return Collections.emptySet();
        return graph.incomingEdgesOf(node).stream()
            .map(graph::getEdgeSource)
            .collect(Collectors.toSet());
    }

    @Override
    public Set<CodeNode> getOutgoingNodes(CodeNode node) {
        if (!graph.containsVertex(node)) return Collections.emptySet();
        return graph.outgoingEdgesOf(node).stream()
            .map(graph::getEdgeTarget)
            .collect(Collectors.toSet());
    }

    @Override
    public Set<CodeNode> getRelatedNodes(CodeNode node, RelationshipType type) {
        if (!graph.containsVertex(node)) return Collections.emptySet();
        return graph.outgoingEdgesOf(node).stream()
            .filter(edge -> edge.getType() == type)
            .map(graph::getEdgeTarget)
            .collect(Collectors.toSet());
    }

    @Override
    public Set<Set<CodeNode>> findCycles() {
        TarjanSimpleCycles<CodeNode, Relationship> cycleDetector = new TarjanSimpleCycles<>(graph);
        List<List<CodeNode>> cycles = cycleDetector.findSimpleCycles();
        return cycles.stream()
            .map(HashSet::new)
            .collect(Collectors.toSet());
    }

    @Override
    public Set<CodeNode> getTransitiveDependencies(CodeNode node) {
        if (!graph.containsVertex(node)) return Collections.emptySet();
        Set<CodeNode> result = new HashSet<>();
        BreadthFirstIterator<CodeNode, Relationship> iterator =
            new BreadthFirstIterator<>(graph, node);
        while (iterator.hasNext()) {
            CodeNode next = iterator.next();
            if (!next.equals(node)) {
                result.add(next);
            }
        }
        return result;
    }

    @Override
    public Set<CodeNode> getTransitiveDependents(CodeNode node) {
        // Reverse traversal - find all nodes that depend on this one
        Set<CodeNode> result = new HashSet<>();
        Set<CodeNode> visited = new HashSet<>();
        Queue<CodeNode> queue = new LinkedList<>();
        queue.add(node);

        while (!queue.isEmpty()) {
            CodeNode current = queue.poll();
            for (CodeNode incoming : getIncomingNodes(current)) {
                if (!visited.contains(incoming)) {
                    visited.add(incoming);
                    result.add(incoming);
                    queue.add(incoming);
                }
            }
        }
        return result;
    }

    @Override
    public String exportToJson() {
        try {
            ObjectNode root = objectMapper.createObjectNode();

            ArrayNode nodesArray = objectMapper.createArrayNode();
            for (CodeNode node : graph.vertexSet()) {
                ObjectNode nodeJson = objectMapper.createObjectNode();
                nodeJson.put("id", node.getId());
                nodeJson.put("name", node.getName());
                nodeJson.put("type", node.getType().name());
                nodeJson.put("path", node.getPath());

                ObjectNode propsJson = objectMapper.createObjectNode();
                for (Map.Entry<String, String> entry : node.getProperties().entrySet()) {
                    propsJson.put(entry.getKey(), entry.getValue());
                }
                nodeJson.set("properties", propsJson);
                nodesArray.add(nodeJson);
            }
            root.set("nodes", nodesArray);

            ArrayNode edgesArray = objectMapper.createArrayNode();
            for (Relationship edge : graph.edgeSet()) {
                ObjectNode edgeJson = objectMapper.createObjectNode();
                edgeJson.put("source", graph.getEdgeSource(edge).getId());
                edgeJson.put("target", graph.getEdgeTarget(edge).getId());
                edgeJson.put("type", edge.getType().name());
                edgeJson.put("confidence", edge.getConfidence());
                edgesArray.add(edgeJson);
            }
            root.set("edges", edgesArray);

            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            LOG.error("Failed to export graph to JSON", e);
            return "{}";
        }
    }

    @Override
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("nodeCount", getNodeCount());
        stats.put("edgeCount", getEdgeCount());

        // Count by type
        Map<String, Long> nodesByType = graph.vertexSet().stream()
            .collect(Collectors.groupingBy(
                n -> n.getType().name(),
                Collectors.counting()
            ));
        stats.put("nodesByType", nodesByType);

        // Count edges by type
        Map<String, Long> edgesByType = graph.edgeSet().stream()
            .collect(Collectors.groupingBy(
                e -> e.getType().name(),
                Collectors.counting()
            ));
        stats.put("edgesByType", edgesByType);

        // Cycle count
        stats.put("cycleCount", findCycles().size());

        return stats;
    }

    @Override
    public synchronized void clear() {
        Set<CodeNode> allNodes = new HashSet<>(graph.vertexSet());
        graph.removeAllVertices(allNodes);
        LOG.info("Graph cleared");
    }

    @Override
    public void scanJcrPath(String path) {
        LOG.info("Scanning JCR path: {}", path);

        Map<String, Object> authInfo = new HashMap<>();
        authInfo.put(ResourceResolverFactory.SUBSERVICE, "dede-scanner");

        try (ResourceResolver resolver = resolverFactory.getServiceResourceResolver(authInfo)) {
            Resource root = resolver.getResource(path);
            if (root != null) {
                scanResource(root, 0);
            } else {
                LOG.warn("Resource not found: {}", path);
            }
        } catch (Exception e) {
            LOG.error("Failed to scan JCR path: {}", path, e);
        }
    }

    private void scanResource(Resource resource, int depth) {
        if (depth > config.maxDepth()) return;

        String path = resource.getPath();

        // Check excluded paths
        for (String excluded : config.excludedPaths()) {
            if (path.startsWith(excluded)) return;
        }

        try {
            Node jcrNode = resource.adaptTo(Node.class);
            if (jcrNode == null) return;

            String primaryType = jcrNode.getPrimaryNodeType().getName();

            // Detect AEM Components
            if ("cq:Component".equals(primaryType)) {
                processComponent(resource, jcrNode);
            }

            // Detect Sling Models (via OSGi metadata, if available)
            // Detect HTL files
            if (path.endsWith(".html") && path.contains("/components/")) {
                processHtlFile(resource);
            }

            // Recurse into children
            for (Resource child : resource.getChildren()) {
                scanResource(child, depth + 1);
            }

        } catch (RepositoryException e) {
            LOG.debug("Error processing resource: {}", path, e);
        }
    }

    private void processComponent(Resource resource, Node jcrNode) throws RepositoryException {
        String path = resource.getPath();
        String componentPath = path.replace("/apps/", "").replace("/conf/", "");

        CodeNode componentNode = new CodeNode(
            "res:" + componentPath,
            resource.getName(),
            NodeType.JCR_RESOURCE_TYPE,
            componentPath,
            path
        );

        // Extract properties
        PropertyIterator props = jcrNode.getProperties();
        while (props.hasNext()) {
            Property prop = props.nextProperty();
            if (!prop.isMultiple()) {
                String name = prop.getName();
                if (!name.startsWith("jcr:") && !name.startsWith("cq:")) {
                    componentNode.getProperties().put(name, prop.getString());
                }
            }
        }

        // Check for proxy component
        if (jcrNode.hasProperty("sling:resourceSuperType")) {
            String superType = jcrNode.getProperty("sling:resourceSuperType").getString();
            componentNode.getProperties().put("isProxy", "true");
            componentNode.getProperties().put("sling:resourceSuperType", superType);

            // Create super type node and relationship
            CodeNode superTypeNode = new CodeNode(
                "res:" + superType,
                superType,
                NodeType.JCR_RESOURCE_TYPE,
                superType,
                null
            );
            addNode(superTypeNode);
            addEdge(componentNode, superTypeNode, RelationshipType.EXTENDS);
        }

        addNode(componentNode);
        LOG.debug("Processed component: {}", componentPath);
    }

    private void processHtlFile(Resource resource) {
        String path = resource.getPath();
        CodeNode htlNode = new CodeNode(
            "htl:" + path,
            resource.getName(),
            NodeType.HTL_FILE,
            path,
            path
        );
        addNode(htlNode);

        // Try to link to parent component
        String componentPath = path.substring(0, path.lastIndexOf('/'));
        findNodeById("res:" + componentPath.replace("/apps/", ""))
            .ifPresent(componentNode ->
                addEdge(componentNode, htlNode, RelationshipType.DEFINES)
            );
    }

    @Override
    public List<String> suggestRefactoring() {
        List<String> suggestions = new ArrayList<>();

        // Detect cycles
        Set<Set<CodeNode>> cycles = findCycles();
        if (!cycles.isEmpty()) {
            for (Set<CodeNode> cycle : cycles) {
                String ids = cycle.stream().map(CodeNode::getId).collect(Collectors.joining(", "));
                suggestions.add("CRITICAL [Circular Dependency]: Cycle detected between [" + ids + "]");
            }
        }

        // Detect highly coupled components (high fan-in + fan-out)
        for (CodeNode node : findNodesByType(NodeType.JCR_RESOURCE_TYPE)) {
            int fanIn = getIncomingNodes(node).size();
            int fanOut = getOutgoingNodes(node).size();
            if (fanIn + fanOut > 20) {
                suggestions.add("REFACTOR [High Coupling]: Component '" + node.getName() +
                    "' has " + (fanIn + fanOut) + " connections. Consider splitting.");
            }
        }

        // Detect orphan components (no incoming or outgoing edges)
        for (CodeNode node : findNodesByType(NodeType.JCR_RESOURCE_TYPE)) {
            if (getIncomingNodes(node).isEmpty() && getOutgoingNodes(node).isEmpty()) {
                // Skip proxy components
                if (!"true".equals(node.getProperties().get("isProxy"))) {
                    suggestions.add("REVIEW [Orphan Component]: '" + node.getName() +
                        "' has no connections. May be unused.");
                }
            }
        }

        return suggestions;
    }
}
