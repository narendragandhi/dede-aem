package com.dede.intelligence;

import com.dede.domain.GraphService;
import com.dede.domain.model.CodeNode;
import com.dede.domain.model.NodeType;
import com.dede.domain.model.Relationship;
import com.dede.domain.model.RelationshipType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Graph-based AEM Cloud Service readiness checks: deprecated service usage, OSGi
 * configurations, servlet registration and Sling Model patterns, read from the dependency
 * graph built by ProjectScanner.
 *
 * <p>Source-level rules live in the forbidden-API catalog (ForbiddenApiScanner). Gates and
 * reports combine both through {@link com.dede.cloud.CloudFindings}; do not add a second
 * source-file rule list here.
 */
@Service
public class CloudReadinessAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(CloudReadinessAnalyzer.class);

    private final GraphService graphService;
    private final List<CloudIssue> issues = new ArrayList<>();

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
