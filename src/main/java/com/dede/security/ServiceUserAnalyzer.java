package com.dede.security;

import com.dede.domain.GraphService;
import com.dede.domain.model.CodeNode;
import com.dede.domain.model.NodeType;
import com.dede.domain.model.RelationshipType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Analyzes service user mappings and credentials configurations in AEM projects.
 *
 * Service users are the recommended way to access JCR in AEM Cloud Service,
 * replacing deprecated administrative sessions (loginAdministrative).
 *
 * This analyzer:
 * - Detects service user mapping configurations
 * - Parses mapping entries (bundle:subService=systemUser)
 * - Links service users to the bundles that use them
 * - Validates service user configurations
 */
@Component
public class ServiceUserAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(ServiceUserAnalyzer.class);

    private final GraphService graphService;

    // Pattern for service user mapping: bundleSymbolicName:subServiceName=systemUser
    // or bundleSymbolicName=systemUser (without subservice)
    private static final Pattern MAPPING_PATTERN = Pattern.compile(
        "^([^:=]+)(?::([^=]+))?=(.+)$"
    );

    // Known service user mapper PIDs
    private static final Set<String> SERVICE_USER_MAPPER_PIDS = Set.of(
        "org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl",
        "org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended"
    );

    public ServiceUserAnalyzer(GraphService graphService) {
        this.graphService = graphService;
    }

    /**
     * Analyzes all service user configurations in the graph.
     * Returns a summary of service user mappings.
     */
    public ServiceUserSummary analyzeServiceUsers() {
        Set<CodeNode> configNodes = graphService.getAllNodes().stream()
                .filter(n -> n.getType() == NodeType.OSGI_CONFIG ||
                            n.getType() == NodeType.OSGI_CONFIG_FACTORY)
                .filter(this::isServiceUserMappingConfig)
                .collect(Collectors.toSet());

        log.info("Found {} service user mapping configurations", configNodes.size());

        List<ServiceUserMapping> mappings = new ArrayList<>();
        List<ServiceUserIssue> issues = new ArrayList<>();

        for (CodeNode configNode : configNodes) {
            List<ServiceUserMapping> nodeMappings = parseServiceUserMappings(configNode);
            mappings.addAll(nodeMappings);

            // Validate and collect issues
            issues.addAll(validateMappings(configNode, nodeMappings));
        }

        // Link service users to bundles
        linkServiceUsersToBundles(mappings);

        return new ServiceUserSummary(mappings, issues, configNodes.size());
    }

    /**
     * Checks if a config node is a service user mapping configuration.
     */
    public boolean isServiceUserMappingConfig(CodeNode configNode) {
        String pid = configNode.getProperties().get("pid");
        String factoryPid = configNode.getProperties().get("factoryPid");

        return SERVICE_USER_MAPPER_PIDS.stream().anyMatch(p ->
            p.equals(pid) || p.equals(factoryPid) ||
            (pid != null && pid.startsWith(p)) ||
            (factoryPid != null && factoryPid.startsWith(p))
        );
    }

    /**
     * Parses service user mappings from a configuration node.
     */
    public List<ServiceUserMapping> parseServiceUserMappings(CodeNode configNode) {
        List<ServiceUserMapping> mappings = new ArrayList<>();

        // Look for user.mapping property (array format, comma-separated in our storage)
        String userMappingValue = configNode.getProperties().get("config.user.mapping");
        if (userMappingValue == null) {
            // Try alternate property names
            userMappingValue = configNode.getProperties().get("config.user.default.mapping");
        }

        if (userMappingValue == null || userMappingValue.isBlank()) {
            return mappings;
        }

        String runMode = configNode.getProperties().get("runMode");
        String configPath = configNode.getFilePath();

        // Parse each mapping entry
        for (String entry : userMappingValue.split(",")) {
            entry = entry.trim();
            if (entry.isEmpty()) {
                continue;
            }

            ServiceUserMapping mapping = parseMappingEntry(entry, configNode, runMode, configPath);
            if (mapping != null) {
                mappings.add(mapping);
            }
        }

        return mappings;
    }

    /**
     * Parses a single mapping entry.
     * Format: bundleSymbolicName:subServiceName=systemUser
     *    or:  bundleSymbolicName=systemUser
     */
    private ServiceUserMapping parseMappingEntry(String entry, CodeNode configNode,
                                                  String runMode, String configPath) {
        Matcher matcher = MAPPING_PATTERN.matcher(entry);
        if (!matcher.matches()) {
            log.warn("Invalid service user mapping format: {}", entry);
            return null;
        }

        String bundleSymbolicName = matcher.group(1).trim();
        String subServiceName = matcher.group(2) != null ? matcher.group(2).trim() : null;
        String systemUser = matcher.group(3).trim();

        return new ServiceUserMapping(
            bundleSymbolicName,
            subServiceName,
            systemUser,
            runMode,
            configPath,
            configNode
        );
    }

    /**
     * Validates service user mappings and returns issues found.
     */
    private List<ServiceUserIssue> validateMappings(CodeNode configNode,
                                                     List<ServiceUserMapping> mappings) {
        List<ServiceUserIssue> issues = new ArrayList<>();

        if (mappings.isEmpty()) {
            issues.add(new ServiceUserIssue(
                configNode,
                ServiceUserIssue.Severity.WARNING,
                "Empty service user mapping configuration",
                "Configuration has no user.mapping entries"
            ));
            return issues;
        }

        for (ServiceUserMapping mapping : mappings) {
            // Check for common anti-patterns
            if (mapping.systemUser().equals("admin")) {
                issues.add(new ServiceUserIssue(
                    configNode,
                    ServiceUserIssue.Severity.CRITICAL,
                    "Service user mapped to 'admin'",
                    String.format("Bundle '%s' is mapped to admin user. " +
                        "Use a dedicated service user with minimal permissions.",
                        mapping.bundleSymbolicName())
                ));
            }

            // Check for wildcard mappings (security risk)
            if (mapping.bundleSymbolicName().contains("*")) {
                issues.add(new ServiceUserIssue(
                    configNode,
                    ServiceUserIssue.Severity.HIGH,
                    "Wildcard bundle mapping",
                    String.format("Wildcard bundle '%s' in service user mapping. " +
                        "Use specific bundle symbolic names.",
                        mapping.bundleSymbolicName())
                ));
            }

            // Check for missing bundle (bundle doesn't exist in project)
            Optional<CodeNode> bundle = findBundle(mapping.bundleSymbolicName());
            if (bundle.isEmpty()) {
                issues.add(new ServiceUserIssue(
                    configNode,
                    ServiceUserIssue.Severity.INFO,
                    "Bundle not found in project",
                    String.format("Bundle '%s' referenced in mapping but not found. " +
                        "This may be an external bundle or a typo.",
                        mapping.bundleSymbolicName())
                ));
            }
        }

        return issues;
    }

    /**
     * Links service user mappings to their corresponding bundle nodes.
     */
    private void linkServiceUsersToBundles(List<ServiceUserMapping> mappings) {
        for (ServiceUserMapping mapping : mappings) {
            Optional<CodeNode> bundle = findBundle(mapping.bundleSymbolicName());

            if (bundle.isPresent()) {
                // Create relationship: Bundle -> Config (USES_SERVICE_USER)
                // Note: We use CONFIG_BY as the relationship type since it indicates
                // the bundle is configured by this service user mapping
                graphService.addEdge(bundle.get(), mapping.configNode(), RelationshipType.CONFIG_BY);

                log.debug("Linked bundle {} to service user mapping {}",
                    mapping.bundleSymbolicName(), mapping.systemUser());
            }
        }
    }

    /**
     * Finds a bundle node by its symbolic name.
     */
    private Optional<CodeNode> findBundle(String symbolicName) {
        return graphService.getAllNodes().stream()
                .filter(n -> n.getType() == NodeType.BUNDLE)
                .filter(n -> symbolicName.equals(n.getProperties().get("Bundle-SymbolicName")) ||
                            symbolicName.equals(n.getName()))
                .findFirst();
    }

    /**
     * Gets all service users used by a specific bundle.
     */
    public List<String> getServiceUsersForBundle(String bundleSymbolicName) {
        return analyzeServiceUsers().mappings().stream()
                .filter(m -> bundleSymbolicName.equals(m.bundleSymbolicName()))
                .map(ServiceUserMapping::systemUser)
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * Gets all bundles that use a specific service user.
     */
    public List<String> getBundlesForServiceUser(String systemUser) {
        return analyzeServiceUsers().mappings().stream()
                .filter(m -> systemUser.equals(m.systemUser()))
                .map(ServiceUserMapping::bundleSymbolicName)
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * Checks if a bundle has a service user mapping configured.
     */
    public boolean hasServiceUserMapping(String bundleSymbolicName) {
        return analyzeServiceUsers().mappings().stream()
                .anyMatch(m -> bundleSymbolicName.equals(m.bundleSymbolicName()));
    }

    /**
     * Detects bundles that might need service user mappings but don't have them.
     * Looks for bundles that use ResourceResolverFactory or SlingRepository
     * but have no service user mapping.
     */
    public List<CodeNode> detectBundlesWithoutServiceUsers() {
        Set<String> bundlesWithMappings = analyzeServiceUsers().mappings().stream()
                .map(ServiceUserMapping::bundleSymbolicName)
                .collect(Collectors.toSet());

        // Find bundles that consume ResourceResolverFactory or SlingRepository
        return graphService.getAllNodes().stream()
                .filter(n -> n.getType() == NodeType.BUNDLE)
                .filter(n -> {
                    String symbolicName = n.getProperties().get("Bundle-SymbolicName");
                    if (symbolicName == null) {
                        symbolicName = n.getName();
                    }
                    return !bundlesWithMappings.contains(symbolicName);
                })
                .filter(this::usesResourceResolver)
                .collect(Collectors.toList());
    }

    /**
     * Checks if a bundle uses ResourceResolverFactory (indicating it might need a service user).
     */
    private boolean usesResourceResolver(CodeNode bundle) {
        // Check import packages
        String importPackages = bundle.getProperties().get("Import-Package");
        if (importPackages != null) {
            return importPackages.contains("org.apache.sling.api.resource") ||
                   importPackages.contains("javax.jcr");
        }
        return false;
    }

    // --- Record classes ---

    /**
     * Represents a single service user mapping entry.
     */
    public record ServiceUserMapping(
        String bundleSymbolicName,
        String subServiceName,
        String systemUser,
        String runMode,
        String configPath,
        CodeNode configNode
    ) {
        /**
         * Returns the full mapping key (bundle:subservice or just bundle).
         */
        public String getMappingKey() {
            if (subServiceName != null) {
                return bundleSymbolicName + ":" + subServiceName;
            }
            return bundleSymbolicName;
        }
    }

    /**
     * Represents an issue found during service user analysis.
     */
    public record ServiceUserIssue(
        CodeNode configNode,
        Severity severity,
        String title,
        String description
    ) {
        public enum Severity {
            INFO, WARNING, HIGH, CRITICAL
        }
    }

    /**
     * Summary of service user analysis results.
     */
    public record ServiceUserSummary(
        List<ServiceUserMapping> mappings,
        List<ServiceUserIssue> issues,
        int configCount
    ) {
        public int getMappingCount() {
            return mappings.size();
        }

        public int getIssueCount() {
            return issues.size();
        }

        public int getCriticalIssueCount() {
            return (int) issues.stream()
                .filter(i -> i.severity() == ServiceUserIssue.Severity.CRITICAL)
                .count();
        }

        public Set<String> getUniqueServiceUsers() {
            return mappings.stream()
                .map(ServiceUserMapping::systemUser)
                .collect(Collectors.toSet());
        }

        public Set<String> getUniqueBundles() {
            return mappings.stream()
                .map(ServiceUserMapping::bundleSymbolicName)
                .collect(Collectors.toSet());
        }
    }
}
