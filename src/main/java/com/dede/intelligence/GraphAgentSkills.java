package com.dede.intelligence;

import com.dede.domain.GraphService;
import com.dede.domain.model.CodeNode;
import com.dede.domain.model.NodeType;
import com.dede.domain.model.RelationshipType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class GraphAgentSkills {

    private final GraphService graphService;

    public GraphAgentSkills(GraphService graphService) {
        this.graphService = graphService;
    }

    /**
     * AI-Driven Refactoring Suggestions based on Graph Topology
     */
    public List<String> suggestRefactoring() {
        List<String> suggestions = new ArrayList<>();

        // 1. Detect Circular Dependencies (Core Legacy Dede Feature)
        Set<Set<CodeNode>> cycles = graphService.findCycles();
        if (!cycles.isEmpty()) {
            cycles.forEach(cycle -> {
                String ids = cycle.stream().map(CodeNode::getId).collect(Collectors.joining(", "));
                suggestions.add("CRITICAL [Circular Dependency]: Cycle detected between [" + ids + 
                    "]. This can cause OSGi startup flapping and inconsistent service states. (Legacy Dede Feature)");
            });
        }

        // 2. Detect God Bundles (High Fan-In/Fan-Out)
        graphService.getAllNodes().stream()
            .filter(n -> n.getType() == NodeType.BUNDLE)
            .forEach(bundle -> {
                int fanIn = graphService.getIncomingNodes(bundle).size();
                int fanOut = graphService.getOutgoingNodes(bundle).size();
                if (fanIn + fanOut > 50) {
                    suggestions.add("REFACTOR [God Bundle]: Bundle '" + bundle.getName() + "' has high coupling (" + 
                        (fanIn + fanOut) + " edges). Suggestion: Split into 'api', 'core-logic', and 'util' bundles.");
                }
            });

        // No blanket "com.day.cq is legacy" suggestion: most com.day.cq APIs (PageManager,
        // wcm.api, commons, ...) are fully supported on AEM as a Cloud Service. The APIs that
        // really are forbidden are covered precisely by ForbiddenApiScanner's catalog.

        // 3. Detect Dangling Services (Provided but not Consumed)
        List<CodeNode> dangling = graphService.getAllNodes().stream()
            .filter(n -> n.getType() == NodeType.OSGI_SERVICE)
            .filter(svc -> !isWhiteboardService(svc))
            .filter(svc -> !"true".equals(svc.getProperties().get("isExported")))
            .filter(svc -> graphService.getInboundRelatedNodes(svc, RelationshipType.PROVIDES).stream().noneMatch(c -> c.getType() == NodeType.OSGI_COMPONENT))
            .filter(svc -> graphService.getInboundRelatedNodes(svc, RelationshipType.CONSUMES).isEmpty())
            .limit(5)
            .toList();
        if (!dangling.isEmpty()) {
            suggestions.add("REFACTOR [Dead Code]: Found OSGi services with zero consumers (e.g., " + dangling.get(0).getName() + ").");
        }

        // 4. Detect Zombie Resource Types (Code exists but not in Content)
        // Build a set of resource type names that are used as sling:resourceSuperType
        // by any other node in the graph — these are abstract supertypes, not zombies.
        Set<String> usedAsSuperType = graphService.getAllNodes().stream()
            .filter(n -> n.getProperties().containsKey("sling:resourceSuperType"))
            .map(n -> n.getProperties().get("sling:resourceSuperType"))
            .collect(Collectors.toSet());

        // Build prefix set: if "core/wcm/components/text" exists, then "core",
        // "core/wcm", and "core/wcm/components" are namespace containers, not zombies.
        Set<String> namespacePrefixes = graphService.getAllNodes().stream()
            .filter(n -> n.getType() == NodeType.JCR_RESOURCE_TYPE)
            .map(CodeNode::getName)
            .filter(name -> name.contains("/"))
            .flatMap(name -> {
                List<String> prefixes = new ArrayList<>();
                int idx = name.indexOf('/');
                while (idx != -1) {
                    prefixes.add(name.substring(0, idx));
                    idx = name.indexOf('/', idx + 1);
                }
                return prefixes.stream();
            })
            .collect(Collectors.toSet());

        graphService.getAllNodes().stream()
            .filter(n -> n.getType() == NodeType.JCR_RESOURCE_TYPE)
            .filter(res -> !"true".equals(res.getProperties().get("isExported")))
            .filter(res -> !"true".equals(res.getProperties().get("isProxy")))
            .filter(res -> {
                String name = res.getName();

                // Skip namespace container paths (prefix of another resource type)
                if (namespacePrefixes.contains(name)) return false;

                // Skip versioned abstract supertypes: path ends with /v<N>
                if (name.matches(".*/v\\d+$")) return false;

                // Skip resource types used as sling:resourceSuperType elsewhere
                if (usedAsSuperType.contains(name)) return false;

                // Skip if this node has ANY graph relationships (HTL defines it, EXTENDS it, etc.)
                if (!graphService.getOutgoingNodes(res).isEmpty()) return false;
                if (!graphService.getIncomingNodes(res).isEmpty()) return false;

                // Skip well-known AEM structural patterns that are never directly instantiated
                if (name.contains("_cq_dialog") || name.contains("_cq_design_dialog") ||
                    name.contains("_cq_editConfig") || name.contains("_cq_template") ||
                    name.contains("_cq_htmlTag") ||   // HTML tag wrapper nodes
                    name.contains("clientlibs") || name.contains("/i18n") ||
                    name.contains("/new") || name.contains("/form/") ||
                    name.endsWith(".json") || name.endsWith(".dir") ||
                    name.contains("dialog") ||                             // Granite UI dialogs (any position)
                    name.contains("/editor/") || name.endsWith("/edit")) {  // Touch UI edit overlays
                    return false;
                }

                // Skip components that declare a supertype themselves
                if (res.getProperties().containsKey("sling:resourceSuperType")) return false;

                // Require at least 4 path segments — anything shorter is a namespace
                if (name.split("/").length < 4) return false;

                return true;
            })
            .limit(5)
            .forEach(res -> {
                suggestions.add("REFACTOR [Zombie Code]: ResourceType '" + res.getName() +
                    "' is defined in code but never instantiated in JCR content or HTL.");
            });

        return suggestions;
    }

    /**
     * Original Skills...
     */
    public String detectCycles() {
        Set<Set<CodeNode>> cycles = graphService.findCycles();
        if (cycles.isEmpty()) return "No circular dependencies detected between bundles.";

        return "Architectural Warning: Circular dependencies found in the following node clusters:\n- " +
                cycles.stream()
                        .map(set -> set.stream().map(CodeNode::getId).collect(Collectors.joining(", ")))
                        .collect(Collectors.joining("\n- "));
    }

    public String analyzeImpact(String nodeId) {
        return graphService.findNodeById(nodeId).map(node -> {
            Set<CodeNode> direct = graphService.getIncomingNodes(node);
            Set<CodeNode> transitive = graphService.getTransitiveIncomingNodes(node);
            
            if (transitive.isEmpty()) return "This node has no consumers. Blast radius is zero.";
            
            StringBuilder sb = new StringBuilder();
            sb.append("Blast Radius Analysis for: ").append(nodeId).append("\n");
            sb.append("--------------------------------------------------\n");
            sb.append("DIRECT CONSUMERS (").append(direct.size()).append("):\n- ");
            sb.append(direct.stream().map(n -> n.getType() + ": " + n.getId()).collect(Collectors.joining("\n- ")));
            
            Set<CodeNode> indirect = new java.util.HashSet<>(transitive);
            indirect.removeAll(direct);
            
            if (!indirect.isEmpty()) {
                sb.append("\n\nTRANSITIVE CONSUMERS (").append(indirect.size()).append("):\n- ");
                sb.append(indirect.stream().limit(20).map(n -> n.getType() + ": " + n.getId()).collect(Collectors.joining("\n- ")));
                if (indirect.size() > 20) sb.append("\n... and ").append(indirect.size() - 20).append(" more.");
            }
            
            return sb.toString();
        }).orElse("Symbol not found: " + nodeId);
    }

    public String explainWiring(String sourceBundleId, String targetBundleId) {
        return "Bundle " + sourceBundleId + " is wired to " + targetBundleId + " via Package Imports.";
    }

    public String findDanglingServices() {
        List<CodeNode> dangling = graphService.getAllNodes().stream()
                .filter(n -> n.getType() == com.dede.domain.model.NodeType.OSGI_SERVICE)
                .filter(svc -> !isWhiteboardService(svc))
                .filter(svc -> !graphService.getInboundRelatedNodes(svc, RelationshipType.PROVIDES).isEmpty())
                .filter(svc -> graphService.getInboundRelatedNodes(svc, RelationshipType.CONSUMES).isEmpty())
                .toList();

        if (dangling.isEmpty()) return "No dangling services found. All provided services have at least one consumer.";
        
        return "The following services are provided but never consumed:\n- " + 
                dangling.stream().map(CodeNode::getId).collect(Collectors.joining("\n- "));
    }

    public String traceSlingModel(String resourceType) {
        return graphService.findNodeById("res:" + resourceType).map(node -> {
            Set<CodeNode> models = graphService.getRelatedNodes(node, RelationshipType.ADAPTS_TO);
            if (models.isEmpty()) return "No Sling Model found for resource type: " + resourceType;
            
            return "Resource Type " + resourceType + " adapts to:\n- " + 
                    models.stream().map(CodeNode::getId).collect(Collectors.joining("\n- "));
        }).orElse("Resource Type not found: " + resourceType);
    }

    /**
     * Whiteboard services are consumed by the framework (Sling engine, scheduler, event
     * admin, job manager, ...) rather than through a @Reference in project code, so having
     * no in-project consumer is normal, not dead code. Matched by simple name because
     * {@code service = Servlet.class} yields a node named "Servlet".
     */
    private static final Set<String> WHITEBOARD_SERVICES = Set.of(
        "Servlet", "Filter", "Runnable", "Job", "JobConsumer", "JobExecutor",
        "EventHandler", "EventListener", "ResourceChangeListener", "ExternalResourceChangeListener",
        "WorkflowProcess", "ParticipantStepChooser", "AdapterFactory", "Injector",
        "InjectAnnotationProcessorFactory", "InjectAnnotationProcessorFactory2", "ImplementationPicker",
        "ViaProvider", "ResourceProvider", "ResourceDecorator", "ResourceTransformer",
        "AuthenticationHandler", "AuthenticationInfoPostProcessor", "HealthCheck",
        "ContentDistributionListener", "ReplicationContentFilterFactory", "PostProcessor",
        "SlingPostProcessor", "PreProcessor", "SlingPostOperation", "PostOperation",
        "TransformerFactory", "GeneratorFactory", "SerializerFactory", "BindingsValuesProvider",
        "ResourceListener", "RequestListener", "ServletRequestListener", "ServletContextListener"
    );

    private static boolean isWhiteboardService(CodeNode svc) {
        String name = svc.getName();
        String simpleName = name.substring(name.lastIndexOf('.') + 1);
        return WHITEBOARD_SERVICES.contains(simpleName);
    }

    public List<String> findSymbol(String name) {
        return graphService.getAllNodes().stream()
                .filter(n -> n.getName().toLowerCase().contains(name.toLowerCase()))
                .map(CodeNode::getId)
                .limit(10)
                .toList();
    }
}
