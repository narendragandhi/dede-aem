package com.dede.cloud;

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
 * {@link #criticalCount()} here. Opt-in {@link ExtendedChecks} findings (content packages,
 * legacy paths, service users) are included when enabled.
 */
public record CloudFindings(ForbiddenApiScanner.ScanResult forbiddenApis, CloudReadinessReport readiness,
                            List<Finding> extended) {

    public static final String SOURCE_FORBIDDEN_API = "forbidden-api";
    public static final String SOURCE_CLOUD_READINESS = "cloud-readiness";

    /** One finding from either source, in a common shape. */
    public record Finding(String source, String category, String severity, String file, int line,
                          String message, String target, String type, String replacement, String cwe) {}

    /** Default checks only (no {@link ExtendedChecks}). */
    public CloudFindings(ForbiddenApiScanner.ScanResult forbiddenApis, CloudReadinessReport readiness) {
        this(forbiddenApis, readiness, List.of());
    }

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
        findings.addAll(extended);
        return findings;
    }

    /** Severities in descending order; a gate threshold fails on its level and everything above it. */
    public static final List<String> SEVERITY_ORDER = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO");

    /**
     * Normalizes a user-supplied gate threshold ("high", "HIGH") or throws with the accepted
     * values, so a typo fails loudly instead of silently disabling the gate.
     */
    public static String parseThreshold(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!SEVERITY_ORDER.contains(normalized)) {
            throw new IllegalArgumentException("Unknown severity '" + value + "'; expected one of "
                + String.join(", ", SEVERITY_ORDER));
        }
        return normalized;
    }

    /** Findings at {@code threshold} or more severe. Unknown finding severities never match. */
    public List<Finding> atOrAbove(String threshold) {
        int limit = SEVERITY_ORDER.indexOf(parseThreshold(threshold));
        return all().stream().filter(f -> {
            int rank = SEVERITY_ORDER.indexOf(f.severity());
            return rank >= 0 && rank <= limit;
        }).toList();
    }

    public List<Finding> critical() {
        return atOrAbove("CRITICAL");
    }

    public int criticalCount() {
        // Derived from critical() so the count and the failure message can never disagree.
        return critical().size();
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
        return failureMessage("CRITICAL");
    }

    /**
     * Gate failure message listing every finding at {@code threshold} or above, or null when
     * there are none. Shared by the CLI's --fail-on and the Maven plugin's failOnSeverity.
     */
    public String failureMessage(String threshold) {
        String level = parseThreshold(threshold);
        List<Finding> failing = atOrAbove(level);
        if (failing.isEmpty()) {
            return null;
        }
        String label = "CRITICAL".equals(level) ? "CRITICAL" : level + "-or-above";
        StringBuilder message = new StringBuilder(failing.size() + " " + label + " finding(s):\n");
        for (Finding f : failing) {
            message.append("  - ");
            if (!"CRITICAL".equals(level)) message.append(f.severity()).append(' ');
            message.append('[').append(f.category()).append("] ").append(f.message());
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
