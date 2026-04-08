package com.dede.cloud;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates a supply-chain security report for an AEM/OSGi project.
 *
 * Supply-chain attacks in AEM bundles typically use:
 * - OS process spawning (Runtime, ProcessBuilder)
 * - Remote class loading (URLClassLoader)
 * - Reflective payload delivery (Class.forName with dynamic names)
 *
 * This report surfaces CRITICAL and HIGH supply-chain violations with
 * risk scoring and remediation guidance, separate from cloud-readiness findings.
 */
public class SupplyChainReport {

    private static final Logger log = LoggerFactory.getLogger(SupplyChainReport.class);

    private static final Set<String> SUPPLY_CHAIN_CATEGORIES = Set.of(
        "SUPPLY_CHAIN_PROCESS_SPAWN",
        "SUPPLY_CHAIN_REMOTE_CLASSLOAD",
        "SUPPLY_CHAIN_REFLECTION_LOAD"
    );

    private final List<ForbiddenApiScanner.ForbiddenApiViolation> violations;
    private final Path projectRoot;

    public SupplyChainReport(List<ForbiddenApiScanner.ForbiddenApiViolation> allViolations,
                              Path projectRoot) {
        this.violations = allViolations.stream()
            .filter(v -> SUPPLY_CHAIN_CATEGORIES.contains(v.category()))
            .toList();
        this.projectRoot = projectRoot;
    }

    /**
     * Returns only the supply-chain violations from the full scan.
     */
    public List<ForbiddenApiScanner.ForbiddenApiViolation> getViolations() {
        return violations;
    }

    /**
     * Severity-weighted risk score in range [0, 100].
     *
     * Weights: CRITICAL=20, HIGH=5, MEDIUM=1.
     * No per-severity cap — every additional finding increases the score until saturation at 100,
     * preserving signal across the full range (4 CRITICAL findings score higher than 3).
     */
    public int getRiskScore() {
        int score = violations.stream().mapToInt(v -> switch (v.severity()) {
            case CRITICAL -> 20;
            case HIGH     -> 5;
            case MEDIUM   -> 1;
            default       -> 0;
        }).sum();
        return Math.min(score, 100);
    }

    public boolean isCritical() {
        return violations.stream()
            .anyMatch(v -> v.severity() == ForbiddenApiCatalog.Severity.CRITICAL);
    }

    public Map<String, List<ForbiddenApiScanner.ForbiddenApiViolation>> getViolationsByCategory() {
        return violations.stream()
            .collect(Collectors.groupingBy(ForbiddenApiScanner.ForbiddenApiViolation::category));
    }

    /**
     * Renders a Markdown report suitable for CI output or PR comments.
     */
    public String toMarkdown() {
        if (violations.isEmpty()) {
            return "## Supply-Chain Security Report\n\n✅ No supply-chain risk indicators detected.\n";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## Supply-Chain Security Report\n\n");
        sb.append(String.format("**Risk Score:** %d/100", getRiskScore()));
        if (isCritical()) {
            sb.append(" 🔴 CRITICAL");
        } else {
            sb.append(" 🟡 HIGH");
        }
        sb.append("\n\n");
        sb.append(String.format("**Findings:** %d supply-chain risk indicator(s)\n\n", violations.size()));

        Map<String, List<ForbiddenApiScanner.ForbiddenApiViolation>> byCategory = getViolationsByCategory();
        for (Map.Entry<String, List<ForbiddenApiScanner.ForbiddenApiViolation>> entry : byCategory.entrySet()) {
            sb.append(String.format("### %s\n\n", entry.getKey()));
            List<ForbiddenApiScanner.ForbiddenApiViolation> catViolations = entry.getValue();
            if (!catViolations.isEmpty()) {
                sb.append(String.format("_%s_\n\n", catViolations.get(0).description()));
                sb.append("| File | Line | Method |\n");
                sb.append("|------|------|--------|\n");
                for (ForbiddenApiScanner.ForbiddenApiViolation v : catViolations) {
                    String relPath = projectRoot.relativize(Path.of(v.filePath())).toString();
                    sb.append(String.format("| `%s` | %d | `%s` |\n",
                        relPath, v.line(), v.target()));
                }
                sb.append(String.format("\n**Fix:** %s\n\n", catViolations.get(0).replacement()));
            }
        }

        return sb.toString();
    }

    /**
     * Renders a compact JSON-serializable summary.
     */
    public Map<String, Object> toSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("riskScore", getRiskScore());
        summary.put("critical", isCritical());
        summary.put("totalFindings", violations.size());
        summary.put("categories", getViolationsByCategory().entrySet().stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> e.getValue().size()
            )));
        summary.put("findings", violations.stream().map(v -> {
            Map<String, Object> finding = new LinkedHashMap<>();
            finding.put("file", v.filePath());
            finding.put("line", v.line());
            finding.put("method", v.target());
            finding.put("category", v.category());
            finding.put("severity", v.severity().name());
            return finding;
        }).toList());
        return summary;
    }
}
