package com.dede.discovery;

import com.dede.domain.GraphService;
import com.dede.domain.model.CodeNode;
import com.dede.domain.model.NodeType;
import com.dede.domain.model.Relationship;
import com.dede.domain.model.RelationshipType;
import com.dede.domain.util.VersionUtil;
import org.jgrapht.Graph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Links OSGi components, services, and configurations in the graph.
 * Creates relationships between:
 * - Bundles via package imports/exports
 * - Components via service consumption/provision
 * - Configurations to their target services/components
 */
@Component
public class OsgiLinker {

    private static final Logger log = LoggerFactory.getLogger(OsgiLinker.class);

    private final GraphService graphService;

    public OsgiLinker(GraphService graphService) {
        this.graphService = graphService;
    }

    /**
     * Performs all OSGi linking operations.
     */
    public void link() {
        linkBundles();
        linkConfigurationsToServices();
    }

    /**
     * Links bundles via package imports/exports and service consumption.
     */
    private void linkBundles() {
        Graph<CodeNode, Relationship> graph = graphService.getGraph();
        Set<CodeNode> bundles = graph.vertexSet().stream()
                .filter(n -> n.getType() == NodeType.BUNDLE)
                .collect(Collectors.toSet());

        log.info("Linking {} bundles...", bundles.size());

        for (CodeNode importingBundle : bundles) {
            // 1. Version-Aware Package Linking
            Set<Relationship> importEdges = graph.outgoingEdgesOf(importingBundle).stream()
                    .filter(e -> e.getType() == RelationshipType.IMPORTS)
                    .collect(Collectors.toSet());

            for (Relationship importEdge : importEdges) {
                CodeNode pkg = graph.getEdgeTarget(importEdge);
                String range = importEdge.getProperties().get("versionRange");

                Set<Relationship> exportEdges = graph.incomingEdgesOf(pkg).stream()
                        .filter(e -> e.getType() == RelationshipType.EXPORTS)
                        .collect(Collectors.toSet());

                for (Relationship exportEdge : exportEdges) {
                    CodeNode exportingBundle = graph.getEdgeSource(exportEdge);
                    String exportVer = exportEdge.getProperties().get("version");

                    if (!importingBundle.equals(exportingBundle) && VersionUtil.matches(exportVer, range)) {
                        graphService.addEdge(importingBundle, exportingBundle, RelationshipType.WIRES_TO);
                    }
                }
            }

            // 2. Link via OSGi Services
            Set<CodeNode> consumedServices = graphService.getRelatedNodes(importingBundle, RelationshipType.CONSUMES);
            for (CodeNode svc : consumedServices) {
                Set<CodeNode> providingBundles = graphService.getInboundRelatedNodes(svc, RelationshipType.PROVIDES);
                for (CodeNode providingBundle : providingBundles) {
                    if (!importingBundle.equals(providingBundle)) {
                        graphService.addEdge(importingBundle, providingBundle, RelationshipType.WIRES_TO);
                    }
                }
            }
        }
    }

    /**
     * Links OSGi configurations to their target services/components.
     * Creates CONFIG_BY and CONFIGURES relationships.
     */
    public void linkConfigurationsToServices() {
        Graph<CodeNode, Relationship> graph = graphService.getGraph();

        // Find all OSGi config nodes
        Set<CodeNode> configNodes = graph.vertexSet().stream()
                .filter(n -> n.getType() == NodeType.OSGI_CONFIG ||
                            n.getType() == NodeType.OSGI_CONFIG_FACTORY)
                .collect(Collectors.toSet());

        // Find all OSGi components and services
        Set<CodeNode> componentNodes = graph.vertexSet().stream()
                .filter(n -> n.getType() == NodeType.OSGI_COMPONENT ||
                            n.getType() == NodeType.OSGI_SERVICE)
                .collect(Collectors.toSet());

        // Build a map of PID -> Component for efficient lookup
        Map<String, List<CodeNode>> pidToComponents = buildPidToComponentMap(componentNodes);

        log.info("Linking {} configurations to {} components...", configNodes.size(), componentNodes.size());

        int linkedCount = 0;
        for (CodeNode configNode : configNodes) {
            String targetPid = getTargetPid(configNode);
            if (targetPid == null) {
                continue;
            }

            List<CodeNode> targetComponents = pidToComponents.get(targetPid);
            if (targetComponents != null && !targetComponents.isEmpty()) {
                for (CodeNode component : targetComponents) {
                    // Config -> Component: CONFIGURES
                    Relationship configuresEdge = graphService.addEdge(configNode, component, RelationshipType.CONFIGURES);

                    // Add run mode as property on the edge
                    String runMode = configNode.getProperties().get("runMode");
                    if (runMode != null) {
                        configuresEdge.getProperties().put("runMode", runMode);
                    }

                    // Component -> Config: CONFIG_BY (inverse relationship)
                    graphService.addEdge(component, configNode, RelationshipType.CONFIG_BY);

                    linkedCount++;
                    log.debug("Linked config {} to component {}", configNode.getName(), component.getName());
                }
            } else {
                // No direct component match - try to link via class name patterns
                linkConfigToClassByName(configNode, targetPid, componentNodes);
            }
        }

        log.info("Created {} config-to-component links", linkedCount);
    }

    /**
     * Builds a map from PID to components that declare that PID.
     */
    private Map<String, List<CodeNode>> buildPidToComponentMap(Set<CodeNode> componentNodes) {
        Map<String, List<CodeNode>> pidMap = new HashMap<>();

        for (CodeNode component : componentNodes) {
            // Get declared PIDs from component properties
            String componentPid = component.getProperties().get("service.pid");
            if (componentPid != null) {
                pidMap.computeIfAbsent(componentPid, k -> new ArrayList<>()).add(component);
            }

            // Also map by factory PID if present
            String factoryPid = component.getProperties().get("service.factoryPid");
            if (factoryPid != null) {
                pidMap.computeIfAbsent(factoryPid, k -> new ArrayList<>()).add(component);
            }

            // Map by fully qualified class name (common pattern)
            String className = component.getSignature();
            if (className != null) {
                pidMap.computeIfAbsent(className, k -> new ArrayList<>()).add(component);
            }
        }

        return pidMap;
    }

    /**
     * Gets the target PID that a configuration is meant to configure.
     */
    private String getTargetPid(CodeNode configNode) {
        // For factory configs, use the factory PID
        if (configNode.getType() == NodeType.OSGI_CONFIG_FACTORY) {
            return configNode.getProperties().get("factoryPid");
        }
        // For regular configs, use the PID
        return configNode.getProperties().get("pid");
    }

    /**
     * Attempts to link a config to a component by matching class names.
     * This handles cases where the PID matches the fully qualified class name.
     */
    private void linkConfigToClassByName(CodeNode configNode, String pid, Set<CodeNode> componentNodes) {
        // Extract simple class name from PID (e.g., "com.example.MyService" -> "MyService")
        String simpleClassName = pid.contains(".") ?
            pid.substring(pid.lastIndexOf('.') + 1) : pid;

        for (CodeNode component : componentNodes) {
            String componentName = component.getName();
            String componentSignature = component.getSignature();

            // Match by exact signature (fully qualified name)
            if (pid.equals(componentSignature)) {
                graphService.addEdge(configNode, component, RelationshipType.CONFIGURES);
                graphService.addEdge(component, configNode, RelationshipType.CONFIG_BY);
                log.debug("Linked config {} to component {} by signature match", pid, componentName);
                return;
            }

            // Match by simple name (less reliable, lower confidence)
            if (simpleClassName.equals(componentName)) {
                Relationship edge = graphService.addEdge(configNode, component, RelationshipType.CONFIGURES, 70);
                edge.getProperties().put("matchType", "nameMatch");
                graphService.addEdge(component, configNode, RelationshipType.CONFIG_BY, 70);
                log.debug("Linked config {} to component {} by name match (low confidence)", pid, componentName);
                return;
            }
        }
    }

    /**
     * Links configurations that target a specific service interface.
     * Finds all implementations of the service and links the config to them.
     */
    public void linkConfigToServiceImplementations(CodeNode configNode, String serviceInterface) {
        Graph<CodeNode, Relationship> graph = graphService.getGraph();

        // Find the service interface node
        Optional<CodeNode> serviceNode = graph.vertexSet().stream()
                .filter(n -> n.getType() == NodeType.OSGI_SERVICE &&
                            serviceInterface.equals(n.getSignature()))
                .findFirst();

        if (serviceNode.isPresent()) {
            // Find all components that provide this service
            Set<CodeNode> providers = graphService.getInboundRelatedNodes(
                serviceNode.get(), RelationshipType.PROVIDES);

            for (CodeNode provider : providers) {
                graphService.addEdge(configNode, provider, RelationshipType.CONFIGURES);
                graphService.addEdge(provider, configNode, RelationshipType.CONFIG_BY);
            }
        }
    }

    /**
     * Gets all configurations for a given component.
     */
    public Set<CodeNode> getConfigurationsForComponent(CodeNode component) {
        return graphService.getRelatedNodes(component, RelationshipType.CONFIG_BY);
    }

    /**
     * Gets all configurations for a component, filtered by run mode.
     */
    public Set<CodeNode> getConfigurationsForComponent(CodeNode component, String runMode) {
        return graphService.getRelatedNodes(component, RelationshipType.CONFIG_BY).stream()
                .filter(config -> {
                    String configRunMode = config.getProperties().get("runMode");
                    if (runMode == null) {
                        return configRunMode == null; // Base configs only
                    }
                    return runMode.equals(configRunMode);
                })
                .collect(Collectors.toSet());
    }

    /**
     * Detects configuration conflicts (same PID, different run modes with conflicting values).
     */
    public List<ConfigurationConflict> detectConfigurationConflicts() {
        Graph<CodeNode, Relationship> graph = graphService.getGraph();
        List<ConfigurationConflict> conflicts = new ArrayList<>();

        // Group configs by their target PID
        Map<String, List<CodeNode>> configsByPid = graph.vertexSet().stream()
                .filter(n -> n.getType() == NodeType.OSGI_CONFIG ||
                            n.getType() == NodeType.OSGI_CONFIG_FACTORY)
                .collect(Collectors.groupingBy(this::getTargetPid));

        for (Map.Entry<String, List<CodeNode>> entry : configsByPid.entrySet()) {
            List<CodeNode> configs = entry.getValue();
            if (configs.size() > 1) {
                // Check for overlapping run modes
                for (int i = 0; i < configs.size(); i++) {
                    for (int j = i + 1; j < configs.size(); j++) {
                        CodeNode config1 = configs.get(i);
                        CodeNode config2 = configs.get(j);

                        if (hasOverlappingRunModes(config1, config2)) {
                            conflicts.add(new ConfigurationConflict(
                                entry.getKey(),
                                config1,
                                config2,
                                "Overlapping run modes"
                            ));
                        }
                    }
                }
            }
        }

        return conflicts;
    }

    private boolean hasOverlappingRunModes(CodeNode config1, CodeNode config2) {
        String runMode1 = config1.getProperties().get("runMode");
        String runMode2 = config2.getProperties().get("runMode");

        // Both are base configs (no run mode) - conflict
        if (runMode1 == null && runMode2 == null) {
            return true;
        }

        // One is base, one has run mode - the run mode one will override, no conflict
        if (runMode1 == null || runMode2 == null) {
            return false;
        }

        // Same run mode - conflict
        if (runMode1.equals(runMode2)) {
            return true;
        }

        // Check if one run mode is a subset of the other
        // e.g., "author" and "author.dev" could conflict
        return runMode1.startsWith(runMode2 + ".") || runMode2.startsWith(runMode1 + ".");
    }

    /**
     * Represents a configuration conflict between two configs targeting the same PID.
     */
    public record ConfigurationConflict(
        String pid,
        CodeNode config1,
        CodeNode config2,
        String reason
    ) {}
}
