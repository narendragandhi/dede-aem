package com.dede.intelligence;

import com.dede.domain.GraphService;
import com.dede.domain.model.CodeNode;
import com.dede.domain.model.NodeType;
import com.dede.domain.model.Relationship;
import com.dede.domain.model.RelationshipType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * AEM Cloud Service Readiness Analyzer
 *
 * Analyzes AEM projects for cloud service compatibility by detecting:
 * - Deprecated APIs and patterns
 * - Environment-specific code that won't work in cloud
 * - Hardcoded paths and configurations
 * - Non-cloud-compatible OSGi configurations
 * - JCR write operations in publish instances
 * - Admin session usage
 *
 * Based on Adobe's Cloud Service migration best practices:
 * https://experienceleague.adobe.com/docs/experience-manager-cloud-service/moving/refactoring-tools/index.html
 */
@Service
public class CloudReadinessAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(CloudReadinessAnalyzer.class);

    private final GraphService graphService;
    private final List<CloudIssue> issues = new ArrayList<>();

    // Deprecated API patterns
    private static final Map<Pattern, String> DEPRECATED_APIS = Map.ofEntries(
        Map.entry(Pattern.compile("com\\.day\\.cq\\.commons\\.jcr\\.JcrUtil"),
            "JcrUtil is deprecated. Use org.apache.sling.jcr.api instead"),
        Map.entry(Pattern.compile("org\\.apache\\.sling\\.commons\\.json"),
            "Sling Commons JSON is deprecated. Use javax.json or Jackson"),
        Map.entry(Pattern.compile("com\\.day\\.cq\\.wcm\\.api\\.WCMMode\\.fromRequest"),
            "Direct WCMMode access is discouraged in Cloud. Use request attributes"),
        Map.entry(Pattern.compile("adminSession|getAdministrativeResourceResolver|loginAdministrative"),
            "Admin session/resolver is forbidden in Cloud. Use service users instead"),
        Map.entry(Pattern.compile("session\\.impersonate"),
            "Session impersonation is not allowed in Cloud"),
        Map.entry(Pattern.compile("ResourceResolverFactory\\.USER_IMPERSONATION"),
            "User impersonation is restricted in Cloud Service"),
        Map.entry(Pattern.compile("com\\.day\\.cq\\.dam\\.api\\.AssetManager\\.createAsset"),
            "Direct asset creation deprecated. Use Asset APIs with proper service user"),
        Map.entry(Pattern.compile("com\\.day\\.cq\\.replication\\.ReplicationAction"),
            "Classic replication deprecated in Cloud. Use Sling Content Distribution"),
        Map.entry(Pattern.compile("com\\.day\\.cq\\.workflow\\.exec\\.WorkflowProcess"),
            "Legacy workflow API. Consider Granite workflow or process API"),
        Map.entry(Pattern.compile("adaptTo\\(Node\\.class\\)|adaptTo\\(Session\\.class\\)"),
            "Direct JCR access discouraged. Use Resource API patterns")
    );

    // Hardcoded paths that won't work in cloud
    private static final Map<Pattern, String> HARDCODED_PATHS = Map.ofEntries(
        Map.entry(Pattern.compile("/var/log"), "Log paths don't exist in Cloud"),
        Map.entry(Pattern.compile("/tmp/"), "Temp paths are ephemeral in Cloud"),
        Map.entry(Pattern.compile("/crx/de"), "CRX/DE not available in Cloud prod"),
        Map.entry(Pattern.compile("/system/console"), "Felix console restricted in Cloud"),
        Map.entry(Pattern.compile("localhost:\\d+"), "Hardcoded localhost URLs won't work in Cloud"),
        Map.entry(Pattern.compile("file:/"), "Local file system access not available"),
        Map.entry(Pattern.compile("/apps/[\\w-]+/install"), "Install folders behavior different in Cloud"),
        Map.entry(Pattern.compile("\\.infinity\\.json"), "Infinity JSON selector removed for security")
    );

    // OSGi configuration anti-patterns for cloud
    private static final Map<Pattern, String> OSGI_ANTIPATTERNS = Map.ofEntries(
        Map.entry(Pattern.compile("runmode\\.author\\.dev|runmode\\.publish\\.dev"),
            "Dev runmodes don't exist in Cloud. Use config.author.dev/stage/prod"),
        Map.entry(Pattern.compile("immediate\\s*=\\s*true"),
            "immediate=true can cause startup issues. Use careful ordering"),
        Map.entry(Pattern.compile("configuration-policy\\s*=\\s*\"require\""),
            "Strict config policy may fail if config not deployed properly")
    );

    // Cloud-incompatible patterns in code
    private static final Map<Pattern, String> CLOUD_INCOMPATIBLE = Map.ofEntries(
        Map.entry(Pattern.compile("Thread\\.sleep"),
            "Thread.sleep is unreliable in Cloud's auto-scaling environment"),
        Map.entry(Pattern.compile("new\\s+Thread\\("),
            "Manual thread creation discouraged. Use Sling Jobs or Commons Scheduler"),
        Map.entry(Pattern.compile("Runtime\\.getRuntime\\(\\)\\.exec"),
            "External process execution not allowed in Cloud"),
        Map.entry(Pattern.compile("System\\.getProperty"),
            "System properties differ in Cloud. Use OSGi configuration"),
        Map.entry(Pattern.compile("System\\.getenv"),
            "Environment variables require Cloud Manager config"),
        Map.entry(Pattern.compile("@SlingServlet.*paths\\s*="),
            "Path-based servlets restricted. Use resourceTypes instead"),
        Map.entry(Pattern.compile("session\\.save\\(\\)"),
            "Direct session.save() risky. Use ResourceResolver.commit() or AutoCloseable"),
        Map.entry(Pattern.compile("Class\\.forName"),
            "Dynamic class loading may fail with Cloud classloader"),
        Map.entry(Pattern.compile("ServiceLoader"),
            "ServiceLoader won't find services across OSGi bundles")
    );

    public CloudReadinessAnalyzer(GraphService graphService) {
        this.graphService = graphService;
    }

    /**
     * Run full cloud readiness analysis on the scanned codebase.
     */
    public CloudReadinessReport analyze() {
        issues.clear();
        log.info("Starting AEM Cloud Service Readiness Analysis...");

        // Analyze from graph
        analyzeDeprecatedServiceUsage();
        analyzeOsgiConfigurations();
        analyzeServletPatterns();
        analyzeSlingModelPatterns();

        // Build report
        CloudReadinessReport report = new CloudReadinessReport();
        report.setIssues(new ArrayList<>(issues));
        report.calculateScore();

        log.info("Cloud Readiness Analysis complete: {} issues found, score: {}%",
            issues.size(), report.getScore());

        return report;
    }

    /**
     * Analyze source files directly for cloud incompatibilities.
     */
    public CloudReadinessReport analyzeSourceFiles(Path projectPath) throws IOException {
        issues.clear();
        log.info("Analyzing source files for Cloud readiness: {}", projectPath);

        // Walk Java files
        try (var stream = Files.walk(projectPath)) {
            stream.filter(p -> p.toString().endsWith(".java"))
                  .filter(p -> !p.toString().contains("/test/"))
                  .forEach(this::analyzeJavaFile);
        }

        // Walk OSGi configs
        try (var stream = Files.walk(projectPath)) {
            stream.filter(p -> p.toString().matches(".*\\.(cfg|config|cfg\\.json)$"))
                  .forEach(this::analyzeOsgiConfigFile);
        }

        // Walk XML configs
        try (var stream = Files.walk(projectPath)) {
            stream.filter(p -> p.toString().endsWith(".xml"))
                  .filter(p -> p.toString().contains("/config"))
                  .forEach(this::analyzeXmlConfig);
        }

        CloudReadinessReport report = new CloudReadinessReport();
        report.setIssues(new ArrayList<>(issues));
        report.calculateScore();

        return report;
    }

    private void analyzeJavaFile(Path file) {
        try {
            String content = Files.readString(file);
            String relativePath = file.toString();

            // Check deprecated APIs
            DEPRECATED_APIS.forEach((pattern, message) -> {
                if (pattern.matcher(content).find()) {
                    issues.add(new CloudIssue(
                        Severity.HIGH,
                        Category.DEPRECATED_API,
                        message,
                        relativePath,
                        findLineNumber(content, pattern)
                    ));
                }
            });

            // Check hardcoded paths
            HARDCODED_PATHS.forEach((pattern, message) -> {
                if (pattern.matcher(content).find()) {
                    issues.add(new CloudIssue(
                        Severity.MEDIUM,
                        Category.HARDCODED_PATH,
                        message,
                        relativePath,
                        findLineNumber(content, pattern)
                    ));
                }
            });

            // Check cloud-incompatible patterns
            CLOUD_INCOMPATIBLE.forEach((pattern, message) -> {
                if (pattern.matcher(content).find()) {
                    issues.add(new CloudIssue(
                        Severity.HIGH,
                        Category.CLOUD_INCOMPATIBLE,
                        message,
                        relativePath,
                        findLineNumber(content, pattern)
                    ));
                }
            });

        } catch (IOException e) {
            log.warn("Failed to analyze file: {}", file, e);
        }
    }

    private void analyzeOsgiConfigFile(Path file) {
        try {
            String content = Files.readString(file);
            String relativePath = file.toString();

            OSGI_ANTIPATTERNS.forEach((pattern, message) -> {
                if (pattern.matcher(content).find()) {
                    issues.add(new CloudIssue(
                        Severity.MEDIUM,
                        Category.OSGI_CONFIG,
                        message,
                        relativePath,
                        findLineNumber(content, pattern)
                    ));
                }
            });

            // Check for cloud-invalid config folder naming
            if (relativePath.contains("/config.author/") || relativePath.contains("/config.publish/")) {
                // These are valid for cloud
            } else if (relativePath.matches(".*config\\.[a-z]+\\.dev.*")) {
                issues.add(new CloudIssue(
                    Severity.MEDIUM,
                    Category.OSGI_CONFIG,
                    "Dev runmode configs may not be needed. Cloud uses dev/stage/prod tiers.",
                    relativePath,
                    0
                ));
            }

        } catch (IOException e) {
            log.warn("Failed to analyze OSGi config: {}", file, e);
        }
    }

    private void analyzeXmlConfig(Path file) {
        try {
            String content = Files.readString(file);
            String relativePath = file.toString();

            // Check for replication agent configs
            if (content.contains("cq:ReplicationConfig") || content.contains("replication/agents")) {
                issues.add(new CloudIssue(
                    Severity.HIGH,
                    Category.CLOUD_INCOMPATIBLE,
                    "Classic replication agents not used in Cloud. Content is distributed automatically.",
                    relativePath,
                    0
                ));
            }

            // Check for workflow launcher configs that might conflict
            if (content.contains("cq:WorkflowLauncher") && content.contains("jcr:content/dam")) {
                issues.add(new CloudIssue(
                    Severity.MEDIUM,
                    Category.CLOUD_INCOMPATIBLE,
                    "Custom DAM workflow launchers may conflict with Cloud processing.",
                    relativePath,
                    0
                ));
            }

        } catch (IOException e) {
            log.warn("Failed to analyze XML config: {}", file, e);
        }
    }

    private void analyzeDeprecatedServiceUsage() {
        var graph = graphService.getGraph();

        // Find services consuming deprecated APIs
        for (CodeNode node : graph.vertexSet()) {
            if (node.getType() == NodeType.OSGI_SERVICE || node.getType() == NodeType.OSGI_COMPONENT) {
                // Check references
                for (Relationship edge : graph.outgoingEdgesOf(node)) {
                    CodeNode target = graph.getEdgeTarget(edge);
                    String targetName = target.getSignature() != null ? target.getSignature() : target.getName();

                    // Check for admin resource resolver usage
                    if (targetName.contains("loginAdministrative") || targetName.contains("getAdministrative")) {
                        issues.add(new CloudIssue(
                            Severity.CRITICAL,
                            Category.DEPRECATED_API,
                            "Service uses admin session/resolver. Must use service users in Cloud.",
                            node.getFilePath() != null ? node.getFilePath() : node.getId(),
                            0
                        ));
                    }
                }
            }
        }
    }

    private void analyzeOsgiConfigurations() {
        var graph = graphService.getGraph();

        for (CodeNode node : graph.vertexSet()) {
            if (node.getType() == NodeType.OSGI_CONFIG) {
                String configName = node.getName();

                // Check for sensitive configurations
                if (configName.contains("password") || configName.contains("secret")) {
                    issues.add(new CloudIssue(
                        Severity.HIGH,
                        Category.SECURITY,
                        "Sensitive config should use Cloud Manager secrets, not plain text.",
                        node.getFilePath() != null ? node.getFilePath() : node.getId(),
                        0
                    ));
                }
            }
        }
    }

    private void analyzeServletPatterns() {
        var graph = graphService.getGraph();

        for (CodeNode node : graph.vertexSet()) {
            if (node.getType() == NodeType.SLING_SERVLET) {
                // Check for path-based servlets
                String props = node.getProperties().getOrDefault("servletPaths", "");
                if (!props.isEmpty()) {
                    issues.add(new CloudIssue(
                        Severity.HIGH,
                        Category.CLOUD_INCOMPATIBLE,
                        "Path-based servlet may be blocked in Cloud. Use resourceTypes instead.",
                        node.getFilePath() != null ? node.getFilePath() : node.getId(),
                        0
                    ));
                }
            }
        }
    }

    private void analyzeSlingModelPatterns() {
        var graph = graphService.getGraph();

        for (CodeNode node : graph.vertexSet()) {
            if (node.getType() == NodeType.SLING_MODEL) {
                // Check for direct JCR usage in models
                for (Relationship edge : graph.outgoingEdgesOf(node)) {
                    CodeNode target = graph.getEdgeTarget(edge);
                    if (target.getName().contains("Session") || target.getName().contains("Node")) {
                        issues.add(new CloudIssue(
                            Severity.MEDIUM,
                            Category.DEPRECATED_API,
                            "Sling Model uses direct JCR API. Prefer Resource API for cloud portability.",
                            node.getFilePath() != null ? node.getFilePath() : node.getId(),
                            0
                        ));
                    }
                }
            }
        }
    }

    private int findLineNumber(String content, Pattern pattern) {
        var matcher = pattern.matcher(content);
        if (matcher.find()) {
            String beforeMatch = content.substring(0, matcher.start());
            return (int) beforeMatch.chars().filter(c -> c == '\n').count() + 1;
        }
        return 0;
    }

    /**
     * Generate a text report suitable for console output.
     */
    public String generateTextReport(CloudReadinessReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append("╔══════════════════════════════════════════════════════════════════════════════╗\n");
        sb.append("║               AEM CLOUD SERVICE READINESS REPORT                             ║\n");
        sb.append("╠══════════════════════════════════════════════════════════════════════════════╣\n");

        // Score with visual indicator
        int score = report.getScore();
        String scoreBar = generateScoreBar(score);
        String verdict = score >= 80 ? "READY" : score >= 50 ? "NEEDS WORK" : "NOT READY";
        String color = score >= 80 ? "🟢" : score >= 50 ? "🟡" : "🔴";

        sb.append(String.format("║  Score: %d/100 %s  %s                                          ║\n",
            score, scoreBar, color));
        sb.append(String.format("║  Verdict: %-66s  ║\n", verdict));
        sb.append("╠══════════════════════════════════════════════════════════════════════════════╣\n");

        // Summary by category
        Map<Category, List<CloudIssue>> byCategory = report.getIssues().stream()
            .collect(Collectors.groupingBy(CloudIssue::getCategory));

        sb.append("║  ISSUES BY CATEGORY                                                          ║\n");
        sb.append("╟──────────────────────────────────────────────────────────────────────────────╢\n");

        for (Category cat : Category.values()) {
            List<CloudIssue> catIssues = byCategory.getOrDefault(cat, List.of());
            if (!catIssues.isEmpty()) {
                long critical = catIssues.stream().filter(i -> i.getSeverity() == Severity.CRITICAL).count();
                long high = catIssues.stream().filter(i -> i.getSeverity() == Severity.HIGH).count();
                long medium = catIssues.stream().filter(i -> i.getSeverity() == Severity.MEDIUM).count();

                sb.append(String.format("║  %-25s %3d issues (🔴%d 🟠%d 🟡%d)                    ║\n",
                    cat.getDisplayName(), catIssues.size(), critical, high, medium));
            }
        }

        sb.append("╠══════════════════════════════════════════════════════════════════════════════╣\n");
        sb.append("║  TOP ISSUES TO FIX                                                           ║\n");
        sb.append("╟──────────────────────────────────────────────────────────────────────────────╢\n");

        // Show top 10 critical/high issues
        report.getIssues().stream()
            .filter(i -> i.getSeverity() == Severity.CRITICAL || i.getSeverity() == Severity.HIGH)
            .limit(10)
            .forEach(issue -> {
                String icon = issue.getSeverity() == Severity.CRITICAL ? "🔴" : "🟠";
                String file = shortenPath(issue.getFile());
                String msg = truncate(issue.getMessage(), 50);
                sb.append(String.format("║  %s %-50s                      ║\n", icon, msg));
                sb.append(String.format("║     └─ %-68s  ║\n", file));
            });

        sb.append("╠══════════════════════════════════════════════════════════════════════════════╣\n");
        sb.append("║  RECOMMENDATIONS                                                             ║\n");
        sb.append("╟──────────────────────────────────────────────────────────────────────────────╢\n");

        if (byCategory.containsKey(Category.DEPRECATED_API)) {
            sb.append("║  • Replace deprecated APIs with Cloud-compatible alternatives                ║\n");
        }
        if (byCategory.containsKey(Category.HARDCODED_PATH)) {
            sb.append("║  • Use OSGi configuration for environment-specific paths                     ║\n");
        }
        if (byCategory.containsKey(Category.CLOUD_INCOMPATIBLE)) {
            sb.append("║  • Review Cloud Service architecture documentation                           ║\n");
        }
        if (byCategory.containsKey(Category.SECURITY)) {
            sb.append("║  • Use Cloud Manager secrets for sensitive configuration                     ║\n");
        }

        sb.append("╚══════════════════════════════════════════════════════════════════════════════╝\n");

        return sb.toString();
    }

    private String generateScoreBar(int score) {
        int filled = score / 10;
        int empty = 10 - filled;
        return "[" + "█".repeat(filled) + "░".repeat(empty) + "]";
    }

    private String shortenPath(String path) {
        if (path == null) return "unknown";
        if (path.length() <= 60) return path;
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash > 0) {
            return "..." + path.substring(Math.max(lastSlash - 40, 0));
        }
        return path.substring(path.length() - 60);
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
    }

    // Enums and DTOs
    public enum Severity {
        CRITICAL(40), HIGH(20), MEDIUM(10), LOW(5);

        private final int scoreImpact;
        Severity(int impact) { this.scoreImpact = impact; }
        public int getScoreImpact() { return scoreImpact; }
    }

    public enum Category {
        DEPRECATED_API("Deprecated APIs"),
        HARDCODED_PATH("Hardcoded Paths"),
        OSGI_CONFIG("OSGi Configuration"),
        CLOUD_INCOMPATIBLE("Cloud Incompatible"),
        SECURITY("Security Issues");

        private final String displayName;
        Category(String name) { this.displayName = name; }
        public String getDisplayName() { return displayName; }
    }

    public static class CloudIssue {
        private final Severity severity;
        private final Category category;
        private final String message;
        private final String file;
        private final int lineNumber;

        public CloudIssue(Severity severity, Category category, String message, String file, int lineNumber) {
            this.severity = severity;
            this.category = category;
            this.message = message;
            this.file = file;
            this.lineNumber = lineNumber;
        }

        public Severity getSeverity() { return severity; }
        public Category getCategory() { return category; }
        public String getMessage() { return message; }
        public String getFile() { return file; }
        public int getLineNumber() { return lineNumber; }
    }

    public static class CloudReadinessReport {
        private List<CloudIssue> issues = new ArrayList<>();
        private int score = 100;

        public List<CloudIssue> getIssues() { return issues; }
        public void setIssues(List<CloudIssue> issues) { this.issues = issues; }
        public int getScore() { return score; }

        public void calculateScore() {
            int deductions = issues.stream()
                .mapToInt(i -> i.getSeverity().getScoreImpact())
                .sum();
            this.score = Math.max(0, 100 - deductions);
        }

        public boolean isCloudReady() {
            return score >= 80 && issues.stream().noneMatch(i -> i.getSeverity() == Severity.CRITICAL);
        }
    }
}
