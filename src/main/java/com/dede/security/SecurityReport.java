package com.dede.security;

import com.dede.cloud.ForbiddenApiScanner;
import com.dede.cloud.SarifReport;
import com.dede.cloud.SupplyChainReport;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Unified security report aggregating findings from all security analyzers:
 *
 * <ul>
 *   <li>{@link SupplyChainReport}       — supply-chain attack indicators</li>
 *   <li>{@link ServletSecurityAuditor}  — servlet exposure and auth issues</li>
 *   <li>{@link XssDetector}             — HTL XSS vulnerabilities</li>
 *   <li>{@link AclAnalyzer}             — overly permissive ACLs</li>
 * </ul>
 *
 * <p>Outputs JSON summary, Markdown, and SARIF 2.1.0.
 */
public class SecurityReport {

    private final SupplyChainReport supplyChain;
    private final ServletSecurityAuditor.AuditResult servletAudit;
    private final XssDetector.ScanResult xssScan;
    private final AclAnalyzer.AnalysisResult aclAnalysis;
    private final Path projectRoot;

    public SecurityReport(SupplyChainReport supplyChain,
                          ServletSecurityAuditor.AuditResult servletAudit,
                          XssDetector.ScanResult xssScan,
                          AclAnalyzer.AnalysisResult aclAnalysis,
                          Path projectRoot) {
        this.supplyChain  = supplyChain;
        this.servletAudit = servletAudit;
        this.xssScan      = xssScan;
        this.aclAnalysis  = aclAnalysis;
        this.projectRoot  = projectRoot;
    }

    /**
     * Overall score 0–100, where 100 = clean.
     * Deductions: CRITICAL=15, HIGH=8, MEDIUM=3, per-finding, floored at 0.
     */
    public int getScore() {
        long critical = countAll(Severity.CRITICAL);
        long high     = countAll(Severity.HIGH);
        long medium   = countAll(Severity.MEDIUM);
        int deductions = (int)(critical * 15 + high * 8 + medium * 3);
        return Math.max(0, 100 - deductions);
    }

    public boolean hasCritical() {
        return countAll(Severity.CRITICAL) > 0;
    }

    public long countAll(Severity severity) {
        return countSupplyChain(severity)
             + countServlets(severity)
             + countXss(severity)
             + countAcl(severity);
    }

    private long countSupplyChain(Severity severity) {
        return supplyChain.getViolations().stream()
            .filter(v -> toUnified(v.severity()) == severity).count();
    }

    private long countServlets(Severity severity) {
        return servletAudit.findings().stream()
            .filter(f -> toUnified(f.severity()) == severity).count();
    }

    private long countXss(Severity severity) {
        return xssScan.findings().stream()
            .filter(f -> toUnified(f.severity()) == severity).count();
    }

    private long countAcl(Severity severity) {
        return aclAnalysis.findings().stream()
            .filter(f -> toUnified(f.severity()) == severity).count();
    }

    /** Normalise per-analyzer severity enums to the unified Severity. */
    private Severity toUnified(Enum<?> sev) {
        return switch (sev.name()) {
            case "CRITICAL" -> Severity.CRITICAL;
            case "HIGH"     -> Severity.HIGH;
            case "MEDIUM"   -> Severity.MEDIUM;
            default         -> Severity.LOW;
        };
    }

    // --- Output formats ---

    public Map<String, Object> toJson() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("score", getScore());
        out.put("critical", countAll(Severity.CRITICAL));
        out.put("high", countAll(Severity.HIGH));
        out.put("medium", countAll(Severity.MEDIUM));

        out.put("supplyChain", supplyChain.toSummary());

        out.put("servletAudit", Map.of(
            "totalFindings", servletAudit.findings().size(),
            "byType", servletAudit.byType().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().name(), e -> e.getValue().size()))
        ));

        out.put("xss", Map.of(
            "totalFindings", xssScan.findings().size(),
            "critical", xssScan.countBySeverity(XssDetector.Severity.CRITICAL),
            "high",     xssScan.countBySeverity(XssDetector.Severity.HIGH),
            "byFile",   xssScan.byFile().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().size()))
        ));

        out.put("acl", Map.of(
            "totalFindings", aclAnalysis.findings().size(),
            "critical", aclAnalysis.countBySeverity(AclAnalyzer.Severity.CRITICAL),
            "high",     aclAnalysis.countBySeverity(AclAnalyzer.Severity.HIGH)
        ));

        return out;
    }

    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Security Report\n\n");

        int score = getScore();
        String badge = score >= 80 ? "🟢" : score >= 50 ? "🟡" : "🔴";
        sb.append(String.format("**Security Score:** %s %d/100\n\n", badge, score));

        appendSummaryTable(sb);
        appendSupplyChain(sb);
        appendServletAudit(sb);
        appendXss(sb);
        appendAcl(sb);

        return sb.toString();
    }

    private void appendSummaryTable(StringBuilder sb) {
        sb.append("## Summary\n\n");
        sb.append("| Category | Critical | High | Medium |\n");
        sb.append("|----------|----------|------|--------|\n");
        sb.append(String.format("| Supply Chain | %d | %d | %d |\n",
            countSupplyChain(Severity.CRITICAL), countSupplyChain(Severity.HIGH),
            countSupplyChain(Severity.MEDIUM)));
        sb.append(String.format("| Servlet Audit | %d | %d | %d |\n",
            countServlets(Severity.CRITICAL), countServlets(Severity.HIGH),
            countServlets(Severity.MEDIUM)));
        sb.append(String.format("| XSS | %d | %d | %d |\n",
            countXss(Severity.CRITICAL), countXss(Severity.HIGH), countXss(Severity.MEDIUM)));
        sb.append(String.format("| ACL | %d | %d | %d |\n\n",
            countAcl(Severity.CRITICAL), countAcl(Severity.HIGH), countAcl(Severity.MEDIUM)));
    }

    private void appendSupplyChain(StringBuilder sb) {
        if (supplyChain.getViolations().isEmpty()) {
            sb.append("## Supply-Chain\n\n✅ No supply-chain indicators.\n\n");
            return;
        }
        sb.append(supplyChain.toMarkdown()).append("\n");
    }

    private void appendServletAudit(StringBuilder sb) {
        var findings = servletAudit.findings();
        if (findings.isEmpty()) {
            sb.append("## Servlet Security\n\n✅ No servlet security issues.\n\n");
            return;
        }
        sb.append("## Servlet Security\n\n");
        sb.append(String.format("**%d finding(s)**\n\n", findings.size()));
        sb.append("| Servlet | Severity | Issue | Recommendation |\n");
        sb.append("|---------|----------|-------|----------------|\n");
        for (var f : findings) {
            String name = f.filePath() != null
                ? Path.of(f.filePath()).getFileName().toString() : f.servletId();
            sb.append(String.format("| `%s` | %s | %s | %s |\n",
                name, f.severity(), f.message().replace("|", "\\|"),
                f.recommendation().replace("|", "\\|")));
        }
        sb.append("\n");
    }

    private void appendXss(StringBuilder sb) {
        var findings = xssScan.findings();
        if (findings.isEmpty()) {
            sb.append("## XSS\n\n✅ No XSS patterns detected.\n\n");
            return;
        }
        sb.append("## XSS\n\n");
        sb.append(String.format("**%d finding(s)**\n\n", findings.size()));
        sb.append("| File | Line | Severity | Issue |\n");
        sb.append("|------|------|----------|-------|\n");
        for (var f : findings) {
            sb.append(String.format("| `%s` | %d | %s | %s |\n",
                f.filePath(), f.line(), f.severity(),
                f.message().replace("|", "\\|")));
        }
        sb.append("\n");
    }

    private void appendAcl(StringBuilder sb) {
        var findings = aclAnalysis.findings();
        if (findings.isEmpty()) {
            sb.append("## ACL\n\n✅ No ACL issues detected.\n\n");
            return;
        }
        sb.append("## ACL\n\n");
        sb.append(String.format("**%d finding(s)**\n\n", findings.size()));
        sb.append("| Path | Severity | Issue |\n");
        sb.append("|------|----------|-------|\n");
        for (var f : findings) {
            sb.append(String.format("| `%s` | %s | %s |\n",
                f.jcrPath(), f.severity(), f.message().replace("|", "\\|")));
        }
        sb.append("\n");
    }

    /**
     * SARIF 2.1.0 combining all findings with CWE mappings.
     */
    public Map<String, Object> toSarif() {
        // Delegate supply-chain findings to the existing SarifReport,
        // then append servlet/XSS/ACL results as additional rules+results.
        List<ForbiddenApiScanner.ForbiddenApiViolation> scViolations = supplyChain.getViolations();
        SarifReport baseReport = new SarifReport(scViolations, projectRoot);
        Map<String, Object> sarif = baseReport.toSarif();

        // Append servlet, XSS, and ACL results into the first run
        @SuppressWarnings("unchecked")
        var runs = (List<Map<String, Object>>) sarif.get("runs");
        @SuppressWarnings("unchecked")
        var results = (List<Map<String, Object>>) runs.get(0).get("results");
        List<Map<String, Object>> allResults = new ArrayList<>(results);

        for (var f : servletAudit.findings()) {
            allResults.add(buildGenericResult(
                "SERVLET_" + f.type().name(),
                f.severity().name(), f.message(),
                f.filePath(), 0));
        }
        for (var f : xssScan.findings()) {
            allResults.add(buildGenericResult(
                "XSS_" + f.type().name(),
                f.severity().name(), f.message(),
                projectRoot.resolve(f.filePath()).toString(), f.line()));
        }
        for (var f : aclAnalysis.findings()) {
            allResults.add(buildGenericResult(
                "ACL_" + f.type().name(),
                f.severity().name(), f.message(),
                f.filePath(), 0));
        }

        runs.get(0).put("results", allResults);
        return sarif;
    }

    private Map<String, Object> buildGenericResult(String ruleId, String severity,
                                                    String message, String filePath, int line) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ruleId", ruleId);
        result.put("level", toSarifLevel(severity));
        result.put("message", Map.of("text", message));
        if (filePath != null) {
            Map<String, Object> region = new LinkedHashMap<>();
            if (line > 0) region.put("startLine", line);
            Map<String, Object> artLoc = new LinkedHashMap<>();
            artLoc.put("uri", filePath.replace('\\', '/'));
            Map<String, Object> physLoc = new LinkedHashMap<>();
            physLoc.put("artifactLocation", artLoc);
            if (!region.isEmpty()) physLoc.put("region", region);
            result.put("locations", List.of(Map.of("physicalLocation", physLoc)));
        }
        return result;
    }

    private String toSarifLevel(String severity) {
        return switch (severity) {
            case "CRITICAL", "HIGH" -> "error";
            case "MEDIUM"           -> "warning";
            default                 -> "note";
        };
    }

    public enum Severity { CRITICAL, HIGH, MEDIUM, LOW }
}
