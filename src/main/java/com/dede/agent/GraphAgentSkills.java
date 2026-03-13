package com.dede.agent;

import com.dede.core.GraphService;
import com.dede.core.model.CodeNode;
import com.dede.core.model.NodeType;
import com.dede.core.model.RelationshipType;
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

        // 3. Detect Legacy AEM APIs (Cloud Readiness)
        List<CodeNode> legacyNodes = graphService.getAllNodes().stream()
            .filter(n -> n.getId().contains("com.day.cq"))
            .limit(5)
            .toList();
        if (!legacyNodes.isEmpty()) {
            suggestions.add("REFACTOR [AEM Cloud]: Legacy 'com.day.cq' APIs found in " + legacyNodes.size() + 
                " locations. Suggestion: Modernize to 'com.adobe.granite' or 'org.apache.sling' equivalents.");
        }

        // 4. Detect Dangling Services (Provided but not Consumed)
        List<CodeNode> dangling = graphService.getAllNodes().stream()
            .filter(n -> n.getType() == NodeType.OSGI_SERVICE)
            .filter(svc -> !"true".equals(svc.getProperties().get("isExported")))
            .filter(svc -> graphService.getInboundRelatedNodes(svc, RelationshipType.PROVIDES).stream().noneMatch(c -> c.getType() == NodeType.OSGI_COMPONENT))
            .filter(svc -> graphService.getInboundRelatedNodes(svc, RelationshipType.CONSUMES).isEmpty())
            .limit(5)
            .toList();
        if (!dangling.isEmpty()) {
            suggestions.add("REFACTOR [Dead Code]: Found OSGi services with zero consumers (e.g., " + dangling.get(0).getName() + ").");
        }

        // 5. Detect Legacy AEM APIs
        long legacyCount = graphService.getAllNodes().stream()
            .filter(n -> n.getFilePath() != null && n.getFilePath().endsWith(".java"))
            .filter(n -> n.getProperties().getOrDefault("imports", "").contains("com.day.cq"))
            .count();
        if (legacyCount > 0) {
            suggestions.add("REFACTOR [AEM Cloud]: Legacy 'com.day.cq' APIs found in " + legacyCount + " locations. Suggestion: Modernize to 'com.adobe.granite' or 'org.apache.sling' equivalents.");
        }

        // 6. Detect Zombie Resource Types (Code exists but not in Content)
        graphService.getAllNodes().stream()
            .filter(n -> n.getType() == NodeType.JCR_RESOURCE_TYPE)
            .filter(res -> !"true".equals(res.getProperties().get("isExported")))
            .forEach(res -> {
                boolean hasContent = !graphService.getInboundRelatedNodes(res, RelationshipType.INSTANTIATED_BY).isEmpty();
                boolean hasHtl = !graphService.getInboundRelatedNodes(res, RelationshipType.REFERENCES).isEmpty();
                if (!hasContent && !hasHtl) {
                    suggestions.add("REFACTOR [Zombie Code]: ResourceType '" + res.getName() + "' is defined in code but never instantiated in JCR content or HTL.");
                }
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

    public String checkCloudReadiness() {
        List<String> legacyPackages = List.of("com.day.cq", "com.adobe.granite.workflow.api");
        List<CodeNode> violations = graphService.getAllNodes().stream()
                .filter(n -> n.getType() == com.dede.core.model.NodeType.PACKAGE)
                .filter(pkg -> legacyPackages.stream().anyMatch(lp -> pkg.getId().contains(lp)))
                .toList();

        if (violations.isEmpty()) return "No major legacy AEM APIs detected. Codebase appears Cloud Ready.";

        return "AEM Cloud Service Readiness Warning: Usage of legacy APIs detected in:\n- " +
                violations.stream().map(CodeNode::getId).collect(Collectors.joining("\n- ")) +
                "\nRecommendation: Migrate to modern com.adobe.granite or com.adobe.cq packages.";
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
                .filter(n -> n.getType() == com.dede.core.model.NodeType.OSGI_SERVICE)
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

    public List<String> findSymbol(String name) {
        return graphService.getAllNodes().stream()
                .filter(n -> n.getName().toLowerCase().contains(name.toLowerCase()))
                .map(CodeNode::getId)
                .limit(10)
                .toList();
    }
}
