package com.dede.cloud;

import com.dede.intelligence.CloudReadinessAnalyzer;
import com.dede.intelligence.CloudReadinessAnalyzer.CloudIssue;
import com.dede.intelligence.CloudReadinessAnalyzer.CloudReadinessReport;
import com.dede.intelligence.CloudReadinessAnalyzer.Category;
import com.dede.intelligence.CloudReadinessAnalyzer.Severity;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates Best Practices Analyzer (BPA) compatible reports.
 *
 * Maps Dede findings to Adobe Cloud Manager CST rule IDs for easy comparison
 * with official Adobe BPA tool output.
 *
 * CST Rules Reference:
 * - CST-1: Deprecated API usage
 * - CST-2: Mutable/immutable content mixed
 * - CST-3: Classic UI usage
 * - CST-4: Reverse replication
 * - CST-5: Custom runmodes
 * - CST-6: Workflow process incompatibility
 * - CST-7: Index issues
 */
@Service
public class BpaReportGenerator {

    private final CloudReadinessAnalyzer cloudReadinessAnalyzer;
    private final ForbiddenApiCatalog forbiddenApiCatalog;
    private final ObjectMapper objectMapper;

    // Mapping from Dede categories to CST rule IDs
    private static final Map<Category, String> CATEGORY_TO_CST = Map.of(
        Category.DEPRECATED_API, "CST-1",
        Category.HARDCODED_PATH, "CST-5",
        Category.OSGI_CONFIG, "CST-5",
        Category.CLOUD_INCOMPATIBLE, "CST-6",
        Category.SECURITY, "SEC-1"
    );

    // Extended CST mappings for specific patterns
    private static final Map<String, CstRule> CST_RULES = new LinkedHashMap<>();
    static {
        CST_RULES.put("CST-1", new CstRule("CST-1", "Deprecated API Usage",
            "Use of deprecated AEM/Sling APIs that are not supported in Cloud Service", "CRITICAL"));
        CST_RULES.put("CST-2", new CstRule("CST-2", "Mixed Mutable/Immutable Content",
            "Content package contains both mutable and immutable content", "MAJOR"));
        CST_RULES.put("CST-3", new CstRule("CST-3", "Classic UI Components",
            "Usage of Classic UI dialogs or components instead of Touch UI", "MINOR"));
        CST_RULES.put("CST-4", new CstRule("CST-4", "Reverse Replication",
            "Reverse replication agents are not supported in Cloud Service", "MAJOR"));
        CST_RULES.put("CST-5", new CstRule("CST-5", "Custom Runmodes",
            "Non-standard runmodes that don't exist in Cloud Service", "MAJOR"));
        CST_RULES.put("CST-6", new CstRule("CST-6", "Workflow Process Incompatibility",
            "Legacy workflow processes not supported in Cloud Service", "MAJOR"));
        CST_RULES.put("CST-7", new CstRule("CST-7", "Index Configuration Issues",
            "Oak index configurations that violate Cloud Service requirements", "BLOCKER"));
        CST_RULES.put("CQBP-72", new CstRule("CQBP-72", "Unclosed ResourceResolver",
            "ResourceResolver not properly closed", "MAJOR"));
        CST_RULES.put("CQBP-75", new CstRule("CQBP-75", "Path-Based Servlet",
            "Servlets registered by path instead of resource type", "MAJOR"));
        CST_RULES.put("CQBP-84", new CstRule("CQBP-84", "ProviderType Implementation",
            "Customer code implements @ProviderType interface", "CRITICAL"));
        CST_RULES.put("AEM-3", new CstRule("AEM-3", "Thread-Unsafe Fields",
            "Non-thread-safe objects stored as class fields", "CRITICAL"));
        CST_RULES.put("AEM-6", new CstRule("AEM-6", "ResourceResolver Cleanup",
            "ResourceResolver must be closed", "CRITICAL"));
        CST_RULES.put("AEM-11", new CstRule("AEM-11", "Admin Session Usage",
            "Use of loginAdministrative or getAdministrativeResourceResolver", "CRITICAL"));
        CST_RULES.put("SEC-1", new CstRule("SEC-1", "Security Configuration",
            "Sensitive data in OSGi configuration", "HIGH"));
    }

    public BpaReportGenerator(CloudReadinessAnalyzer cloudReadinessAnalyzer,
                               ForbiddenApiCatalog forbiddenApiCatalog) {
        this.cloudReadinessAnalyzer = cloudReadinessAnalyzer;
        this.forbiddenApiCatalog = forbiddenApiCatalog;
        this.objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    /**
     * Generate a BPA-style report from cloud readiness analysis.
     */
    public BpaReport generateReport(CloudReadinessReport cloudReport, String projectName) {
        BpaReport report = new BpaReport();
        report.setProjectName(projectName);
        report.setGeneratedAt(Instant.now().toString());
        report.setGeneratedBy("Dede-Java Architectural Intelligence Engine");
        report.setVersion("2.0.0");

        // Convert issues to BPA findings
        List<BpaFinding> findings = new ArrayList<>();
        for (CloudIssue issue : cloudReport.getIssues()) {
            BpaFinding finding = convertToFinding(issue);
            findings.add(finding);
        }
        report.setFindings(findings);

        // Calculate summary
        report.setSummary(calculateSummary(findings, cloudReport.getScore()));

        // Add rule documentation
        report.setRules(new ArrayList<>(CST_RULES.values()));

        return report;
    }

    /**
     * Convert Dede CloudIssue to BPA-style finding.
     */
    private BpaFinding convertToFinding(CloudIssue issue) {
        BpaFinding finding = new BpaFinding();

        // Map to CST rule
        String cstRule = mapToCstRule(issue);
        finding.setRuleId(cstRule);
        finding.setRuleName(CST_RULES.containsKey(cstRule) ?
            CST_RULES.get(cstRule).getName() : "Custom Rule");

        // Map severity
        finding.setSeverity(mapSeverity(issue.getSeverity()));
        finding.setCategory(issue.getCategory().getDisplayName());
        finding.setMessage(issue.getMessage());
        finding.setFile(issue.getFile());
        finding.setLine(issue.getLineNumber());

        // Add remediation guidance
        finding.setRemediation(getRemediation(issue));
        finding.setDocumentationUrl(getDocUrl(cstRule));

        return finding;
    }

    private String mapToCstRule(CloudIssue issue) {
        // Pattern-based mapping for specific issues
        String msg = issue.getMessage().toLowerCase();

        if (msg.contains("admin") && (msg.contains("session") || msg.contains("resolver"))) {
            return "AEM-11";
        }
        if (msg.contains("resourceresolver") && msg.contains("close")) {
            return "CQBP-72";
        }
        if (msg.contains("path") && msg.contains("servlet")) {
            return "CQBP-75";
        }
        if (msg.contains("thread") && (msg.contains("field") || msg.contains("member"))) {
            return "AEM-3";
        }
        if (msg.contains("replication") || msg.contains("replicate")) {
            return "CST-4";
        }
        if (msg.contains("workflow") || msg.contains("workflowprocess")) {
            return "CST-6";
        }
        if (msg.contains("classic") || msg.contains("cq:dialog")) {
            return "CST-3";
        }
        if (msg.contains("index") || msg.contains("lucene")) {
            return "CST-7";
        }

        // Fall back to category mapping
        return CATEGORY_TO_CST.getOrDefault(issue.getCategory(), "CST-1");
    }

    private String mapSeverity(Severity severity) {
        return switch (severity) {
            case CRITICAL -> "BLOCKER";
            case HIGH -> "CRITICAL";
            case MEDIUM -> "MAJOR";
            case LOW -> "MINOR";
        };
    }

    private String getRemediation(CloudIssue issue) {
        String msg = issue.getMessage().toLowerCase();

        if (msg.contains("admin")) {
            return "Replace loginAdministrative/getAdministrativeResourceResolver with " +
                   "ServiceUserMapped and properly configured service users. " +
                   "See: https://experienceleague.adobe.com/docs/experience-manager-cloud-service/content/security/service-users.html";
        }
        if (msg.contains("resourceresolver")) {
            return "Always close ResourceResolver using try-with-resources or explicit close(). " +
                   "Example: try (ResourceResolver resolver = ...) { }";
        }
        if (msg.contains("path") && msg.contains("servlet")) {
            return "Register servlets by resourceType instead of paths. Use sling.servlet.resourceTypes property.";
        }
        if (msg.contains("thread.sleep")) {
            return "Use Sling Jobs API for asynchronous processing instead of Thread.sleep().";
        }
        if (msg.contains("replication")) {
            return "Use Sling Content Distribution API instead of classic replication.";
        }

        return "Review and update code to comply with AEM Cloud Service requirements.";
    }

    private String getDocUrl(String ruleId) {
        return "https://experienceleague.adobe.com/en/docs/experience-manager-cloud-service/content/implementing/using-cloud-manager/test-results/custom-code-quality-rules#"
            + ruleId.toLowerCase();
    }

    private BpaSummary calculateSummary(List<BpaFinding> findings, int score) {
        BpaSummary summary = new BpaSummary();
        summary.setTotalFindings(findings.size());
        summary.setCloudReadinessScore(score);

        Map<String, Long> bySeverity = findings.stream()
            .collect(Collectors.groupingBy(BpaFinding::getSeverity, Collectors.counting()));
        summary.setBlockers(bySeverity.getOrDefault("BLOCKER", 0L).intValue());
        summary.setCritical(bySeverity.getOrDefault("CRITICAL", 0L).intValue());
        summary.setMajor(bySeverity.getOrDefault("MAJOR", 0L).intValue());
        summary.setMinor(bySeverity.getOrDefault("MINOR", 0L).intValue());

        Map<String, Long> byRule = findings.stream()
            .collect(Collectors.groupingBy(BpaFinding::getRuleId, Collectors.counting()));
        summary.setFindingsByRule(byRule.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().intValue())));

        // Verdict
        if (summary.getBlockers() > 0) {
            summary.setVerdict("NOT_READY");
            summary.setVerdictMessage("Critical blockers must be fixed before Cloud migration");
        } else if (summary.getCritical() > 0) {
            summary.setVerdict("NEEDS_REMEDIATION");
            summary.setVerdictMessage("Critical issues require remediation");
        } else if (summary.getMajor() > 5) {
            summary.setVerdict("NEEDS_REVIEW");
            summary.setVerdictMessage("Multiple major issues should be reviewed");
        } else {
            summary.setVerdict("READY");
            summary.setVerdictMessage("Project is ready for Cloud Service migration");
        }

        return summary;
    }

    /**
     * Export report to JSON file.
     */
    public void exportToJson(BpaReport report, Path outputPath) throws IOException {
        objectMapper.writeValue(outputPath.toFile(), report);
    }

    /**
     * Export report to HTML file with BPA-style formatting.
     */
    public void exportToHtml(BpaReport report, Path outputPath) throws IOException {
        String html = generateHtmlReport(report);
        Files.writeString(outputPath, html);
    }

    private String generateHtmlReport(BpaReport report) {
        StringBuilder html = new StringBuilder();

        html.append("""
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Dede BPA-Compatible Report</title>
                <style>
                    :root {
                        --color-blocker: #d32f2f;
                        --color-critical: #f57c00;
                        --color-major: #ffa000;
                        --color-minor: #7cb342;
                        --color-bg: #fafafa;
                        --color-card: #ffffff;
                        --color-border: #e0e0e0;
                    }
                    * { margin: 0; padding: 0; box-sizing: border-box; }
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                        background: var(--color-bg);
                        color: #333;
                        line-height: 1.6;
                    }
                    .container { max-width: 1200px; margin: 0 auto; padding: 20px; }
                    header {
                        background: linear-gradient(135deg, #eb1000 0%, #ff5722 100%);
                        color: white;
                        padding: 30px;
                        margin-bottom: 20px;
                        border-radius: 8px;
                    }
                    header h1 { font-size: 28px; margin-bottom: 8px; }
                    header .subtitle { opacity: 0.9; }
                    header .meta { margin-top: 16px; font-size: 14px; opacity: 0.8; }

                    .summary-grid {
                        display: grid;
                        grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
                        gap: 16px;
                        margin-bottom: 24px;
                    }
                    .summary-card {
                        background: var(--color-card);
                        border-radius: 8px;
                        padding: 20px;
                        box-shadow: 0 2px 4px rgba(0,0,0,0.1);
                        text-align: center;
                    }
                    .summary-card .value {
                        font-size: 36px;
                        font-weight: 700;
                    }
                    .summary-card .label {
                        font-size: 14px;
                        color: #666;
                        text-transform: uppercase;
                    }
                    .summary-card.blocker .value { color: var(--color-blocker); }
                    .summary-card.critical .value { color: var(--color-critical); }
                    .summary-card.major .value { color: var(--color-major); }
                    .summary-card.minor .value { color: var(--color-minor); }
                    .summary-card.score .value { color: #1976d2; }

                    .verdict {
                        background: var(--color-card);
                        border-radius: 8px;
                        padding: 20px;
                        margin-bottom: 24px;
                        box-shadow: 0 2px 4px rgba(0,0,0,0.1);
                        display: flex;
                        align-items: center;
                        gap: 16px;
                    }
                    .verdict.NOT_READY { border-left: 4px solid var(--color-blocker); }
                    .verdict.NEEDS_REMEDIATION { border-left: 4px solid var(--color-critical); }
                    .verdict.NEEDS_REVIEW { border-left: 4px solid var(--color-major); }
                    .verdict.READY { border-left: 4px solid var(--color-minor); }
                    .verdict-icon {
                        width: 48px;
                        height: 48px;
                        border-radius: 50%;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        font-size: 24px;
                    }
                    .verdict.READY .verdict-icon { background: #e8f5e9; }
                    .verdict.NOT_READY .verdict-icon { background: #ffebee; }
                    .verdict.NEEDS_REMEDIATION .verdict-icon { background: #fff3e0; }
                    .verdict.NEEDS_REVIEW .verdict-icon { background: #fffde7; }

                    .findings-section {
                        background: var(--color-card);
                        border-radius: 8px;
                        padding: 20px;
                        margin-bottom: 24px;
                        box-shadow: 0 2px 4px rgba(0,0,0,0.1);
                    }
                    .findings-section h2 {
                        margin-bottom: 16px;
                        padding-bottom: 8px;
                        border-bottom: 1px solid var(--color-border);
                    }

                    .finding {
                        padding: 16px;
                        border: 1px solid var(--color-border);
                        border-radius: 6px;
                        margin-bottom: 12px;
                    }
                    .finding:last-child { margin-bottom: 0; }
                    .finding.BLOCKER { border-left: 4px solid var(--color-blocker); }
                    .finding.CRITICAL { border-left: 4px solid var(--color-critical); }
                    .finding.MAJOR { border-left: 4px solid var(--color-major); }
                    .finding.MINOR { border-left: 4px solid var(--color-minor); }

                    .finding-header {
                        display: flex;
                        justify-content: space-between;
                        align-items: center;
                        margin-bottom: 8px;
                    }
                    .finding-rule {
                        font-weight: 600;
                        color: #1976d2;
                    }
                    .finding-severity {
                        padding: 2px 8px;
                        border-radius: 4px;
                        font-size: 12px;
                        font-weight: 600;
                        color: white;
                    }
                    .finding-severity.BLOCKER { background: var(--color-blocker); }
                    .finding-severity.CRITICAL { background: var(--color-critical); }
                    .finding-severity.MAJOR { background: var(--color-major); color: #333; }
                    .finding-severity.MINOR { background: var(--color-minor); }

                    .finding-message { margin-bottom: 8px; }
                    .finding-file {
                        font-family: monospace;
                        font-size: 13px;
                        color: #666;
                        background: #f5f5f5;
                        padding: 4px 8px;
                        border-radius: 4px;
                        display: inline-block;
                    }
                    .finding-remediation {
                        margin-top: 12px;
                        padding: 12px;
                        background: #e3f2fd;
                        border-radius: 4px;
                        font-size: 14px;
                    }
                    .finding-remediation strong { color: #1565c0; }

                    .rules-section {
                        background: var(--color-card);
                        border-radius: 8px;
                        padding: 20px;
                        box-shadow: 0 2px 4px rgba(0,0,0,0.1);
                    }
                    .rule-item {
                        padding: 12px 0;
                        border-bottom: 1px solid var(--color-border);
                    }
                    .rule-item:last-child { border-bottom: none; }
                    .rule-id { font-weight: 600; color: #1976d2; }
                    .rule-desc { color: #666; font-size: 14px; }

                    footer {
                        text-align: center;
                        padding: 20px;
                        color: #666;
                        font-size: 14px;
                    }
                </style>
            </head>
            <body>
                <div class="container">
            """);

        // Header
        html.append(String.format("""
                    <header>
                        <h1>AEM Cloud Service Readiness Report</h1>
                        <div class="subtitle">BPA-Compatible Analysis by Dede-Java</div>
                        <div class="meta">
                            <div>Project: %s</div>
                            <div>Generated: %s</div>
                        </div>
                    </header>
            """, report.getProjectName(),
                 LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))));

        // Summary cards
        BpaSummary summary = report.getSummary();
        html.append("""
                    <div class="summary-grid">
                        <div class="summary-card score">
                            <div class="value">%d%%</div>
                            <div class="label">Cloud Ready Score</div>
                        </div>
                        <div class="summary-card blocker">
                            <div class="value">%d</div>
                            <div class="label">Blockers</div>
                        </div>
                        <div class="summary-card critical">
                            <div class="value">%d</div>
                            <div class="label">Critical</div>
                        </div>
                        <div class="summary-card major">
                            <div class="value">%d</div>
                            <div class="label">Major</div>
                        </div>
                        <div class="summary-card minor">
                            <div class="value">%d</div>
                            <div class="label">Minor</div>
                        </div>
                    </div>
            """.formatted(summary.getCloudReadinessScore(), summary.getBlockers(),
                         summary.getCritical(), summary.getMajor(), summary.getMinor()));

        // Verdict
        String verdictIcon = switch (summary.getVerdict()) {
            case "READY" -> "&#10004;";
            case "NOT_READY" -> "&#10006;";
            default -> "&#9888;";
        };
        html.append(String.format("""
                    <div class="verdict %s">
                        <div class="verdict-icon">%s</div>
                        <div>
                            <strong>%s</strong>
                            <div>%s</div>
                        </div>
                    </div>
            """, summary.getVerdict(), verdictIcon,
                 summary.getVerdict().replace("_", " "),
                 summary.getVerdictMessage()));

        // Findings
        html.append("""
                    <div class="findings-section">
                        <h2>Findings (%d total)</h2>
            """.formatted(report.getFindings().size()));

        for (BpaFinding finding : report.getFindings()) {
            html.append(String.format("""
                        <div class="finding %s">
                            <div class="finding-header">
                                <span class="finding-rule">%s: %s</span>
                                <span class="finding-severity %s">%s</span>
                            </div>
                            <div class="finding-message">%s</div>
                            <div class="finding-file">%s%s</div>
                            <div class="finding-remediation">
                                <strong>Remediation:</strong> %s
                            </div>
                        </div>
                """, finding.getSeverity(), finding.getRuleId(), finding.getRuleName(),
                     finding.getSeverity(), finding.getSeverity(),
                     escapeHtml(finding.getMessage()),
                     escapeHtml(finding.getFile()),
                     finding.getLine() > 0 ? ":" + finding.getLine() : "",
                     escapeHtml(finding.getRemediation())));
        }

        html.append("</div>"); // findings-section

        // Rules reference
        html.append("""
                    <div class="rules-section">
                        <h2>Rule Reference</h2>
            """);
        for (CstRule rule : CST_RULES.values()) {
            html.append(String.format("""
                        <div class="rule-item">
                            <span class="rule-id">%s</span> - %s
                            <div class="rule-desc">%s</div>
                        </div>
                """, rule.getId(), rule.getName(), rule.getDescription()));
        }
        html.append("</div>");

        // Footer
        html.append("""
                    <footer>
                        Generated by Dede-Java Architectural Intelligence Engine v2.0.0<br>
                        <a href="https://github.com/narendragandhi/dede-java">github.com/narendragandhi/dede-java</a>
                    </footer>
                </div>
            </body>
            </html>
            """);

        return html.toString();
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;");
    }

    // --- DTOs ---

    public static class BpaReport {
        private String projectName;
        private String generatedAt;
        private String generatedBy;
        private String version;
        private BpaSummary summary;
        private List<BpaFinding> findings;
        private List<CstRule> rules;

        // Getters and setters
        public String getProjectName() { return projectName; }
        public void setProjectName(String projectName) { this.projectName = projectName; }
        public String getGeneratedAt() { return generatedAt; }
        public void setGeneratedAt(String generatedAt) { this.generatedAt = generatedAt; }
        public String getGeneratedBy() { return generatedBy; }
        public void setGeneratedBy(String generatedBy) { this.generatedBy = generatedBy; }
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
        public BpaSummary getSummary() { return summary; }
        public void setSummary(BpaSummary summary) { this.summary = summary; }
        public List<BpaFinding> getFindings() { return findings; }
        public void setFindings(List<BpaFinding> findings) { this.findings = findings; }
        public List<CstRule> getRules() { return rules; }
        public void setRules(List<CstRule> rules) { this.rules = rules; }
    }

    public static class BpaSummary {
        private int totalFindings;
        private int cloudReadinessScore;
        private int blockers;
        private int critical;
        private int major;
        private int minor;
        private String verdict;
        private String verdictMessage;
        private Map<String, Integer> findingsByRule;

        public int getTotalFindings() { return totalFindings; }
        public void setTotalFindings(int totalFindings) { this.totalFindings = totalFindings; }
        public int getCloudReadinessScore() { return cloudReadinessScore; }
        public void setCloudReadinessScore(int cloudReadinessScore) { this.cloudReadinessScore = cloudReadinessScore; }
        public int getBlockers() { return blockers; }
        public void setBlockers(int blockers) { this.blockers = blockers; }
        public int getCritical() { return critical; }
        public void setCritical(int critical) { this.critical = critical; }
        public int getMajor() { return major; }
        public void setMajor(int major) { this.major = major; }
        public int getMinor() { return minor; }
        public void setMinor(int minor) { this.minor = minor; }
        public String getVerdict() { return verdict; }
        public void setVerdict(String verdict) { this.verdict = verdict; }
        public String getVerdictMessage() { return verdictMessage; }
        public void setVerdictMessage(String verdictMessage) { this.verdictMessage = verdictMessage; }
        public Map<String, Integer> getFindingsByRule() { return findingsByRule; }
        public void setFindingsByRule(Map<String, Integer> findingsByRule) { this.findingsByRule = findingsByRule; }
    }

    public static class BpaFinding {
        private String ruleId;
        private String ruleName;
        private String severity;
        private String category;
        private String message;
        private String file;
        private int line;
        private String remediation;
        private String documentationUrl;

        public String getRuleId() { return ruleId; }
        public void setRuleId(String ruleId) { this.ruleId = ruleId; }
        public String getRuleName() { return ruleName; }
        public void setRuleName(String ruleName) { this.ruleName = ruleName; }
        public String getSeverity() { return severity; }
        public void setSeverity(String severity) { this.severity = severity; }
        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public String getFile() { return file; }
        public void setFile(String file) { this.file = file; }
        public int getLine() { return line; }
        public void setLine(int line) { this.line = line; }
        public String getRemediation() { return remediation; }
        public void setRemediation(String remediation) { this.remediation = remediation; }
        public String getDocumentationUrl() { return documentationUrl; }
        public void setDocumentationUrl(String documentationUrl) { this.documentationUrl = documentationUrl; }
    }

    public static class CstRule {
        private final String id;
        private final String name;
        private final String description;
        private final String defaultSeverity;

        public CstRule(String id, String name, String description, String defaultSeverity) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.defaultSeverity = defaultSeverity;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public String getDescription() { return description; }
        public String getDefaultSeverity() { return defaultSeverity; }
    }
}
