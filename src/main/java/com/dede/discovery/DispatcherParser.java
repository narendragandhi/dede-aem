package com.dede.discovery;

import com.dede.domain.GraphService;
import com.dede.domain.model.CodeNode;
import com.dede.domain.model.NodeType;
import com.dede.domain.model.RelationshipType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enhanced Dispatcher Configuration Parser.
 *
 * Parses AEM Dispatcher configuration files (.any) to extract:
 * - Farm definitions
 * - Filter rules (allow/deny)
 * - Cache rules
 * - Rewrite rules
 * - Security anti-patterns
 */
@Component
public class DispatcherParser implements ProjectParser {

    private static final Logger log = LoggerFactory.getLogger(DispatcherParser.class);

    private final GraphService graphService;

    // Patterns for parsing dispatcher.any files
    // Filter rule pattern - matches /0001 { ... } style rules
    private static final Pattern FILTER_RULE_PATTERN = Pattern.compile("/(\\d{4})\\s*\\{([^}]+)\\}");
    private static final Pattern FILTER_TYPE_PATTERN = Pattern.compile("/type\\s+\"(allow|deny)\"");
    private static final Pattern URL_PATTERN = Pattern.compile("/url\\s+\"([^\"]+)\"");
    private static final Pattern PATH_PATTERN = Pattern.compile("/path\\s+\"([^\"]+)\"");
    private static final Pattern METHOD_PATTERN = Pattern.compile("/method\\s+\"([^\"]+)\"");
    private static final Pattern SELECTOR_PATTERN = Pattern.compile("/selectors\\s+\"([^\"]+)\"");
    private static final Pattern EXTENSION_PATTERN = Pattern.compile("/extension\\s+\"([^\"]+)\"");
    private static final Pattern GLOB_PATTERN = Pattern.compile("/glob\\s+\"([^\"]+)\"");
    private static final Pattern FARM_NAME_PATTERN = Pattern.compile("/([a-zA-Z][a-zA-Z0-9_-]*)\\s*\\{");

    // Security anti-patterns
    private static final List<String> DANGEROUS_EXTENSIONS = Arrays.asList(
        "json", "xml", "infinity", "tidy", "sysview", "docview",
        "query", "childrenlist", "permissions", "acl", "jcr:content"
    );

    private static final List<String> DANGEROUS_SELECTORS = Arrays.asList(
        "feed", "rss", "pages", "languages", "blueprint", "infinity"
    );

    private static final List<String> DANGEROUS_PATHS = Arrays.asList(
        "/crx", "/system", "/admin", "/dav", "/bin/crxde",
        "/bin/receive", "/bin/replicate.json", "/.json"
    );

    // Track security issues found
    private final List<DispatcherSecurityIssue> securityIssues = new ArrayList<>();

    public DispatcherParser(GraphService graphService) {
        this.graphService = graphService;
    }

    // Files that should have filter sections
    private static final Set<String> FILTER_EXPECTED_FILES = Set.of(
        "dispatcher.any", "filters.any", "filter.any", "default_filters.any"
    );

    // Files that definitely should NOT be checked for filters
    private static final Set<String> NON_FILTER_FILES = Set.of(
        "clientheaders.any", "default_clientheaders.any",
        "renders.any", "default_renders.any",
        "virtualhosts.any", "default_virtualhosts.any",
        "rules.any", "default_rules.any",
        "invalidate.any", "default_invalidate.any",
        "marketing_query_parameters.any", "farms.any"
    );

    @Override
    public boolean supports(Path path) {
        String fileName = path.getFileName().toString();
        // Only parse .any files that are actual dispatcher configs
        // Skip non-filter config fragments
        if (NON_FILTER_FILES.contains(fileName)) {
            return false;
        }
        return fileName.equals("dispatcher.any") ||
               (fileName.contains("filter") && fileName.endsWith(".any"));
    }

    // Pattern to detect $include directives
    private static final Pattern INCLUDE_PATTERN = Pattern.compile("\\$include\\s+\"([^\"]+)\"");

    @Override
    public void parse(Path filePath) {
        try {
            String content = Files.readString(filePath);
            String fileName = filePath.getFileName().toString();

            log.debug("Parsing dispatcher config: {}", filePath);

            // Clear issues for this file
            securityIssues.clear();

            // Check if this file uses $include directives
            boolean hasIncludes = INCLUDE_PATTERN.matcher(content).find();

            // Resolve includes and build complete content
            String resolvedContent = resolveIncludes(content, filePath.getParent());

            // Parse farm definitions if present
            if (resolvedContent.contains("/farms")) {
                parseFarms(resolvedContent, filePath);
            }

            // Parse filter rules if present (check resolved content)
            // Check for either /filter section OR actual filter rules (/0001 { ... })
            boolean hasFilterSection = resolvedContent.contains("/filter");
            boolean hasFilterRules = FILTER_RULE_PATTERN.matcher(resolvedContent).find();

            if (hasFilterSection || hasFilterRules) {
                parseFilterSection(resolvedContent, filePath, "default");
            } else if (!hasIncludes) {
                // Only warn about missing filter if there are no includes
                // (includes might bring in the filter section)
                securityIssues.add(new DispatcherSecurityIssue(
                    "DISP-001",
                    "Missing /filter section",
                    "CRITICAL",
                    "No filter section found - all requests may be allowed by default",
                    filePath.toString()
                ));
            }

            // Parse cache section if present (use resolved content)
            if (resolvedContent.contains("/cache")) {
                parseCacheSection(resolvedContent, filePath);
            }

            // Parse rewrite section if present
            if (resolvedContent.contains("/rewrite")) {
                parseRewriteSection(resolvedContent, filePath);
            }

            // Check for security anti-patterns (use resolved content)
            detectSecurityAntiPatterns(resolvedContent, filePath, hasIncludes);

            // Create violation nodes for security issues
            createSecurityViolationNodes(filePath);

        } catch (Exception e) {
            log.debug("Could not parse dispatcher config {}: {}", filePath, e.getMessage());
        }
    }

    /**
     * Parse farm definitions from dispatcher.any
     */
    private void parseFarms(String content, Path filePath) {
        // Find farm names after /farms section
        int farmsIdx = content.indexOf("/farms");
        if (farmsIdx < 0) return;

        String afterFarms = content.substring(farmsIdx);
        Matcher farmNameMatcher = FARM_NAME_PATTERN.matcher(afterFarms);

        // Skip the "/farms" match itself
        boolean skippedFarms = false;
        while (farmNameMatcher.find()) {
            String farmName = farmNameMatcher.group(1);

            // Skip 'farms' itself
            if (farmName.equals("farms") && !skippedFarms) {
                skippedFarms = true;
                continue;
            }

            // Skip common section names that aren't farm names
            if (farmName.equals("filter") || farmName.equals("cache") ||
                farmName.equals("rules") || farmName.equals("invalidate") ||
                farmName.equals("rewrite") || farmName.equals("vanity_urls")) {
                continue;
            }

            log.debug("Found dispatcher farm: {}", farmName);

            // Create a node for the farm
            CodeNode farmNode = new CodeNode(
                "dispatcher:farm:" + farmName,
                farmName,
                NodeType.DISPATCHER_FILTER,
                "Dispatcher Farm: " + farmName,
                filePath.toString()
            );
            farmNode.getProperties().put("type", "farm");
            graphService.addNode(farmNode);
        }
    }

    /**
     * Parse /filter section and extract rules
     */
    private void parseFilterSection(String content, Path filePath, String farmName) {
        // Track if we have a default deny
        boolean hasDefaultDeny = false;
        int allowCount = 0;
        int denyCount = 0;

        // Find all filter rules in the content (they have /0001 format)
        Matcher ruleMatcher = FILTER_RULE_PATTERN.matcher(content);
        while (ruleMatcher.find()) {
            String ruleId = ruleMatcher.group(1);
            String ruleContent = ruleMatcher.group(2);

            FilterRule rule = parseFilterRule(ruleId, ruleContent);

            if (rule.type.equals("deny")) {
                denyCount++;
                // Check if this is a default deny (early rule denying all)
                if (Integer.parseInt(ruleId) <= 1 && rule.url != null &&
                    (rule.url.equals("*") || rule.url.equals("/.*"))) {
                    hasDefaultDeny = true;
                }
            } else {
                allowCount++;
            }

            // Create node for filter rule
            String nodeId = "dispatcher:filter:" + farmName + ":" + ruleId;
            CodeNode ruleNode = new CodeNode(
                nodeId,
                rule.type.toUpperCase() + " " + (rule.url != null ? rule.url : (rule.glob != null ? rule.glob : "?")),
                NodeType.DISPATCHER_FILTER,
                formatRuleDescription(rule),
                filePath.toString()
            );
            ruleNode.getProperties().put("ruleId", ruleId);
            ruleNode.getProperties().put("filterType", rule.type);
            if (rule.url != null) ruleNode.getProperties().put("url", rule.url);
            if (rule.path != null) ruleNode.getProperties().put("path", rule.path);
            if (rule.method != null) ruleNode.getProperties().put("method", rule.method);
            if (rule.extension != null) ruleNode.getProperties().put("extension", rule.extension);
            if (rule.selectors != null) ruleNode.getProperties().put("selectors", rule.selectors);
            graphService.addNode(ruleNode);

            // Check for dangerous patterns in allow rules
            if (rule.type.equals("allow")) {
                checkAllowRuleSecurity(rule, ruleId, filePath);
            }

            // Link to servlet paths
            linkToServlets(ruleNode, rule);
        }

        // Security check: no default deny
        if (!hasDefaultDeny && allowCount > 0) {
            securityIssues.add(new DispatcherSecurityIssue(
                "DISP-002",
                "Missing default deny rule",
                "MAJOR",
                "No default deny rule found at the start of filter section. " +
                "Best practice is to deny all requests first, then selectively allow.",
                filePath.toString()
            ));
        }

        // Log summary
        if (allowCount + denyCount > 0) {
            log.info("Parsed {} filter rules ({} allow, {} deny) from {}",
                    allowCount + denyCount, allowCount, denyCount, filePath.getFileName());
        }
    }

    /**
     * Parse individual filter rule content
     */
    private FilterRule parseFilterRule(String ruleId, String content) {
        FilterRule rule = new FilterRule();
        rule.id = ruleId;

        Matcher typeMatcher = FILTER_TYPE_PATTERN.matcher(content);
        if (typeMatcher.find()) {
            rule.type = typeMatcher.group(1);
        }

        Matcher urlMatcher = URL_PATTERN.matcher(content);
        if (urlMatcher.find()) {
            rule.url = urlMatcher.group(1);
        }

        Matcher pathMatcher = PATH_PATTERN.matcher(content);
        if (pathMatcher.find()) {
            rule.path = pathMatcher.group(1);
        }

        Matcher methodMatcher = METHOD_PATTERN.matcher(content);
        if (methodMatcher.find()) {
            rule.method = methodMatcher.group(1);
        }

        Matcher selectorMatcher = SELECTOR_PATTERN.matcher(content);
        if (selectorMatcher.find()) {
            rule.selectors = selectorMatcher.group(1);
        }

        Matcher extensionMatcher = EXTENSION_PATTERN.matcher(content);
        if (extensionMatcher.find()) {
            rule.extension = extensionMatcher.group(1);
        }

        Matcher globMatcher = GLOB_PATTERN.matcher(content);
        if (globMatcher.find()) {
            rule.glob = globMatcher.group(1);
        }

        return rule;
    }

    /**
     * Check allow rules for security issues
     */
    private void checkAllowRuleSecurity(FilterRule rule, String ruleId, Path filePath) {
        String checkTarget = rule.url != null ? rule.url : (rule.glob != null ? rule.glob : "");

        // Check for dangerous extensions
        if (rule.extension != null) {
            for (String ext : DANGEROUS_EXTENSIONS) {
                if (rule.extension.contains(ext) || rule.extension.equals("*")) {
                    securityIssues.add(new DispatcherSecurityIssue(
                        "DISP-003",
                        "Potentially dangerous extension allowed",
                        "MAJOR",
                        String.format("Rule %s allows extension '%s' which may expose sensitive data",
                                     ruleId, rule.extension),
                        filePath.toString()
                    ));
                    break;
                }
            }
        }

        // Check for dangerous selectors
        if (rule.selectors != null) {
            for (String sel : DANGEROUS_SELECTORS) {
                if (rule.selectors.contains(sel) || rule.selectors.equals("*")) {
                    securityIssues.add(new DispatcherSecurityIssue(
                        "DISP-004",
                        "Potentially dangerous selector allowed",
                        "MAJOR",
                        String.format("Rule %s allows selector '%s' which may expose sensitive data",
                                     ruleId, rule.selectors),
                        filePath.toString()
                    ));
                    break;
                }
            }
        }

        // Check for dangerous paths
        for (String dangerPath : DANGEROUS_PATHS) {
            if (checkTarget.contains(dangerPath)) {
                securityIssues.add(new DispatcherSecurityIssue(
                    "DISP-005",
                    "Dangerous path allowed",
                    "CRITICAL",
                    String.format("Rule %s allows access to '%s' which should be blocked",
                                 ruleId, dangerPath),
                    filePath.toString()
                ));
            }
        }

        // Check for overly permissive rules
        if (checkTarget.equals("*") || checkTarget.equals("/.*") || checkTarget.equals("/.+")) {
            securityIssues.add(new DispatcherSecurityIssue(
                "DISP-006",
                "Overly permissive allow rule",
                "MAJOR",
                String.format("Rule %s allows all URLs with pattern '%s'. Consider more specific rules.",
                             ruleId, checkTarget),
                filePath.toString()
            ));
        }
    }

    /**
     * Parse /cache section
     */
    private void parseCacheSection(String content, Path filePath) {
        // Count cache rules (patterns like /0001, /0002 inside /cache section)
        int cacheStart = content.indexOf("/cache");
        if (cacheStart < 0) return;

        // Find /rules section within cache
        int rulesStart = content.indexOf("/rules", cacheStart);
        if (rulesStart >= 0) {
            // Count rules within the rules section
            String afterRules = content.substring(rulesStart);
            Matcher ruleMatcher = FILTER_RULE_PATTERN.matcher(afterRules);
            int ruleCount = 0;
            while (ruleMatcher.find()) {
                ruleCount++;
            }

            if (ruleCount > 0) {
                CodeNode cacheNode = new CodeNode(
                    "dispatcher:cache:" + filePath.getFileName(),
                    "Cache Rules",
                    NodeType.CDN_RULE,
                    String.format("Dispatcher cache with %d rules", ruleCount),
                    filePath.toString()
                );
                cacheNode.getProperties().put("ruleCount", String.valueOf(ruleCount));
                graphService.addNode(cacheNode);
            }
        }

        // Check for invalidation handlers
        if (content.indexOf("/invalidate", cacheStart) >= 0) {
            CodeNode invalidateNode = new CodeNode(
                "dispatcher:invalidate:" + filePath.getFileName(),
                "Cache Invalidation",
                NodeType.CDN_RULE,
                "Dispatcher cache invalidation configuration",
                filePath.toString()
            );
            graphService.addNode(invalidateNode);
        }
    }

    /**
     * Resolve $include directives by reading included files.
     * Supports glob patterns like "enabled_farms/*.farm"
     */
    private String resolveIncludes(String content, Path baseDir) {
        StringBuilder resolved = new StringBuilder(content);
        Matcher includeMatcher = INCLUDE_PATTERN.matcher(content);

        // Track replacements to avoid modifying while iterating
        List<String[]> replacements = new ArrayList<>();

        while (includeMatcher.find()) {
            String includePath = includeMatcher.group(1);
            String includeDirective = includeMatcher.group(0);

            try {
                String includedContent = readIncludedContent(baseDir, includePath);
                if (includedContent != null) {
                    replacements.add(new String[]{includeDirective, includedContent});
                }
            } catch (Exception e) {
                log.debug("Could not resolve include: {} from {}", includePath, baseDir);
            }
        }

        // Apply replacements
        String result = content;
        for (String[] replacement : replacements) {
            result = result.replace(replacement[0], replacement[1]);
        }

        return result;
    }

    /**
     * Read content from an included file, supporting glob patterns.
     */
    private String readIncludedContent(Path baseDir, String includePath) {
        try {
            // Handle glob patterns like "enabled_farms/*.farm"
            if (includePath.contains("*")) {
                StringBuilder combined = new StringBuilder();
                String dirPart = includePath.substring(0, includePath.lastIndexOf('/'));
                String globPart = includePath.substring(includePath.lastIndexOf('/') + 1);
                Path searchDir = baseDir.resolve(dirPart);

                if (Files.exists(searchDir) && Files.isDirectory(searchDir)) {
                    try (var stream = Files.newDirectoryStream(searchDir, globPart)) {
                        for (Path file : stream) {
                            if (Files.isRegularFile(file)) {
                                combined.append(Files.readString(file)).append("\n");
                            }
                        }
                    }
                }
                return combined.toString();
            } else {
                // Direct file include
                Path includeFile = baseDir.resolve(includePath);
                if (Files.exists(includeFile)) {
                    return Files.readString(includeFile);
                }
            }
        } catch (Exception e) {
            log.debug("Failed to read include {}: {}", includePath, e.getMessage());
        }
        return "";
    }

    /**
     * Parse /rewrite section for URL mappings
     */
    private void parseRewriteSection(String content, Path filePath) {
        int rewriteStart = content.indexOf("/rewrite");
        if (rewriteStart < 0) return;

        // Count rules in rewrite section
        String afterRewrite = content.substring(rewriteStart);
        Matcher ruleMatcher = FILTER_RULE_PATTERN.matcher(afterRewrite);
        int ruleCount = 0;
        while (ruleMatcher.find()) {
            ruleCount++;
        }

        if (ruleCount > 0) {
            CodeNode rewriteNode = new CodeNode(
                "dispatcher:rewrite:" + filePath.getFileName(),
                "Rewrite Rules",
                NodeType.CDN_RULE,
                String.format("Dispatcher URL rewriting with %d rules", ruleCount),
                filePath.toString()
            );
            rewriteNode.getProperties().put("ruleCount", String.valueOf(ruleCount));
            graphService.addNode(rewriteNode);
        }
    }

    /**
     * Detect general security anti-patterns.
     * Uses resolved content (with includes) for accurate detection.
     */
    private void detectSecurityAntiPatterns(String content, Path filePath, boolean hasIncludes) {
        // Skip minor warnings for files that are just include wrappers
        if (hasIncludes && content.trim().lines().count() < 20) {
            log.debug("Skipping anti-pattern checks for include wrapper: {}", filePath.getFileName());
            return;
        }

        // Check for HTTP method filtering
        if (!content.contains("/method")) {
            securityIssues.add(new DispatcherSecurityIssue(
                "DISP-007",
                "No HTTP method filtering",
                "MINOR",
                "Consider filtering by HTTP method to restrict to GET/HEAD for static content",
                filePath.toString()
            ));
        }

        // Check for query string filtering
        if (!content.contains("/query") && !content.contains("/suffix")) {
            securityIssues.add(new DispatcherSecurityIssue(
                "DISP-008",
                "No query string or suffix filtering",
                "MINOR",
                "Consider adding query/suffix filtering to prevent cache poisoning",
                filePath.toString()
            ));
        }

        // Check for propagate sync requests
        if (content.contains("/propagateSyndPost") && content.contains("\"1\"")) {
            securityIssues.add(new DispatcherSecurityIssue(
                "DISP-009",
                "Syndication propagation enabled",
                "MAJOR",
                "propagateSyndPost is enabled - ensure this is intentional for content sync",
                filePath.toString()
            ));
        }
    }

    /**
     * Link filter rules to servlet paths
     */
    private void linkToServlets(CodeNode filterNode, FilterRule rule) {
        String pathPattern = rule.url != null ? rule.url : (rule.path != null ? rule.path : null);
        if (pathPattern == null) return;

        // Link to /bin/* servlets
        if (pathPattern.contains("/bin/")) {
            String servletPath = pathPattern.split("\\*")[0].split("\\$")[0];
            // Look for existing servlet nodes
            graphService.getAllNodes().stream()
                .filter(node -> node.getType() == NodeType.SLING_SERVLET)
                .filter(node -> {
                    String nodePath = node.getProperties().get("path");
                    return nodePath != null && servletPath.contains(nodePath);
                })
                .forEach(servletNode ->
                    graphService.addEdge(filterNode, servletNode, RelationshipType.REFERENCES, 70)
                );
        }

        // Link to resource type based paths
        if (pathPattern.contains("/content/") || pathPattern.contains("/apps/")) {
            graphService.getAllNodes().stream()
                .filter(node -> node.getType() == NodeType.JCR_RESOURCE_TYPE)
                .filter(node -> pathPattern.contains(node.getName()))
                .forEach(resourceNode ->
                    graphService.addEdge(filterNode, resourceNode, RelationshipType.REFERENCES, 60)
                );
        }
    }

    /**
     * Create vulnerability nodes for detected security issues
     */
    private void createSecurityViolationNodes(Path filePath) {
        for (DispatcherSecurityIssue issue : securityIssues) {
            CodeNode violationNode = new CodeNode(
                "violation:dispatcher:" + issue.ruleId + ":" + filePath.getFileName(),
                issue.ruleId + ": " + issue.title,
                NodeType.VULNERABILITY,
                issue.description,
                issue.filePath
            );
            violationNode.getProperties().put("ruleId", issue.ruleId);
            violationNode.getProperties().put("severity", issue.severity);
            violationNode.getProperties().put("category", "DISPATCHER_SECURITY");
            graphService.addNode(violationNode);

            log.warn("Dispatcher security issue: {} - {} in {}",
                    issue.ruleId, issue.title, filePath.getFileName());
        }
    }

    private String formatRuleDescription(FilterRule rule) {
        StringBuilder sb = new StringBuilder();
        sb.append(rule.type.toUpperCase()).append(" rule");
        if (rule.url != null) sb.append(" URL: ").append(rule.url);
        if (rule.path != null) sb.append(" Path: ").append(rule.path);
        if (rule.method != null) sb.append(" Method: ").append(rule.method);
        if (rule.extension != null) sb.append(" Extension: ").append(rule.extension);
        if (rule.selectors != null) sb.append(" Selectors: ").append(rule.selectors);
        return sb.toString();
    }

    /**
     * Get list of detected security issues (for testing)
     */
    public List<DispatcherSecurityIssue> getSecurityIssues() {
        return Collections.unmodifiableList(securityIssues);
    }

    /**
     * Internal class for filter rule data
     */
    private static class FilterRule {
        String id;
        String type = "allow"; // default
        String url;
        String path;
        String method;
        String selectors;
        String extension;
        String glob;
    }

    /**
     * Internal class for security issues
     */
    public static class DispatcherSecurityIssue {
        public final String ruleId;
        public final String title;
        public final String severity;
        public final String description;
        public final String filePath;

        public DispatcherSecurityIssue(String ruleId, String title, String severity,
                                       String description, String filePath) {
            this.ruleId = ruleId;
            this.title = title;
            this.severity = severity;
            this.description = description;
            this.filePath = filePath;
        }
    }
}
