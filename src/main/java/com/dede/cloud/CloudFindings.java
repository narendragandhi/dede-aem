package com.dede.cloud;

import com.dede.intelligence.CloudReadinessAnalyzer;
import com.dede.intelligence.CloudReadinessAnalyzer.CloudIssue;
import com.dede.intelligence.CloudReadinessAnalyzer.CloudReadinessReport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The single set of cloud-readiness findings every gate and report works from.
 *
 * <p>Dede has two sources of findings: the forbidden-API catalog scan (source code, per file)
 * and the graph-based readiness checks (OSGi wiring, servlets, models). Before this class the
 * CLI gate counted both while the Maven plugin's {@code failOnCritical} counted only the graph
 * checks, so a CRITICAL catalog finding failed one build and passed the other. Both now gate on
 * {@link #criticalCount()} here.
 */
public record CloudFindings(ForbiddenApiScanner.ScanResult forbiddenApis, CloudReadinessReport readiness) {

    public static final String SOURCE_FORBIDDEN_API = "forbidden-api";
    public static final String SOURCE_CLOUD_READINESS = "cloud-readiness";

    /** One finding from either source, in a common shape. */
    public record Finding(String source, String category, String severity, String file, int line,
                          String message, String target, String type, String replacement, String cwe) {}

    public List<Finding> all() {
        List<Finding> findings = new ArrayList<>();
        for (ForbiddenApiScanner.ForbiddenApiViolation v : forbiddenApis.violations()) {
            findings.add(new Finding(SOURCE_FORBIDDEN_API, v.category(), v.severity().name(), v.filePath(),
                v.line(), v.description(), v.target(), v.type().name(), v.replacement(), v.cweId()));
        }
        for (CloudIssue issue : readiness.getIssues()) {
            findings.add(new Finding(SOURCE_CLOUD_READINESS, issue.getCategory().name(), issue.getSeverity().name(),
                issue.getFile(), issue.getLineNumber(), issue.getMessage(), null, null, null, null));
        }
        return findings;
    }

    public List<Finding> critical() {
        return all().stream().filter(f -> "CRITICAL".equals(f.severity())).toList();
    }

    public int criticalCount() {
        return forbiddenApis.getCriticalCount() + (int) readiness.getIssues().stream()
            .filter(i -> i.getSeverity() == CloudReadinessAnalyzer.Severity.CRITICAL)
            .count();
    }

    public Map<String, Long> countBySeverity() {
        Map<String, Long> bySeverity = new TreeMap<>();
        all().forEach(f -> bySeverity.merge(f.severity(), 1L, Long::sum));
        return bySeverity;
    }

    /**
     * Gate failure message listing every CRITICAL finding, or null when there are none.
     * Shared by the CLI's --fail-on-critical and the Maven plugin's failOnCritical.
     */
    public String criticalFailureMessage() {
        List<Finding> critical = critical();
        if (critical.isEmpty()) {
            return null;
        }
        StringBuilder message = new StringBuilder(critical.size() + " CRITICAL finding(s):\n");
        for (Finding f : critical) {
            message.append("  - [").append(f.category()).append("] ").append(f.message());
            if (f.file() != null) {
                message.append(" (").append(f.file());
                if (f.line() > 0) message.append(':').append(f.line());
                message.append(')');
            }
            message.append('\n');
        }
        return message.toString();
    }

    /** Summary block used by the JSON report. */
    public Map<String, Object> summary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", all().size());
        summary.put("critical", criticalCount());
        summary.put("bySeverity", countBySeverity());
        summary.put("cloudReadinessScore", readiness.getScore());
        return summary;
    }
}
