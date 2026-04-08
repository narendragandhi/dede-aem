package com.dede.cloud;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Serializes supply-chain findings as SARIF 2.1.0.
 *
 * SARIF (Static Analysis Results Interchange Format) is the standard format
 * consumed by GitHub Code Scanning, VS Code Problems panel, and SIEMs.
 *
 * Spec: https://docs.oasis-open.org/sarif/sarif/v2.1.0/sarif-v2.1.0.html
 */
public class SarifReport {

    private static final String SARIF_SCHEMA =
        "https://raw.githubusercontent.com/oasis-tcs/sarif-spec/master/Schemata/sarif-schema-2.1.0.json";
    private static final String SARIF_VERSION = "2.1.0";
    private static final String TOOL_NAME    = "dede-java";
    private static final String TOOL_VERSION = "2.0.0";
    private static final String TOOL_URI     = "https://github.com/dede-java/dede-java";

    private final List<ForbiddenApiScanner.ForbiddenApiViolation> violations;
    private final Path projectRoot;

    public SarifReport(List<ForbiddenApiScanner.ForbiddenApiViolation> violations,
                       Path projectRoot) {
        this.violations  = violations;
        this.projectRoot = projectRoot;
    }

    /**
     * Builds the SARIF 2.1.0 document as a nested Map ready for JSON serialization.
     */
    public Map<String, Object> toSarif() {
        Map<String, Object> sarif = new LinkedHashMap<>();
        sarif.put("$schema", SARIF_SCHEMA);
        sarif.put("version", SARIF_VERSION);
        sarif.put("runs", List.of(buildRun()));
        return sarif;
    }

    private Map<String, Object> buildRun() {
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("tool", buildTool());
        run.put("results", buildResults());
        run.put("originalUriBaseIds", Map.of(
            "SRCROOT", Map.of("uri", projectRoot.toUri().toString())
        ));
        return run;
    }

    private Map<String, Object> buildTool() {
        // Deduplicate rules by category name
        Map<String, ForbiddenApiScanner.ForbiddenApiViolation> ruleMap = violations.stream()
            .collect(Collectors.toMap(
                ForbiddenApiScanner.ForbiddenApiViolation::category,
                v -> v,
                (a, b) -> a,          // keep first on collision
                LinkedHashMap::new
            ));

        List<Map<String, Object>> rules = ruleMap.values().stream()
            .map(this::buildRule)
            .toList();

        Map<String, Object> driver = new LinkedHashMap<>();
        driver.put("name", TOOL_NAME);
        driver.put("version", TOOL_VERSION);
        driver.put("informationUri", TOOL_URI);
        driver.put("rules", rules);

        return Map.of("driver", driver);
    }

    private Map<String, Object> buildRule(ForbiddenApiScanner.ForbiddenApiViolation v) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("id", v.category());
        rule.put("name", toRuleName(v.category()));

        Map<String, Object> shortDesc = new LinkedHashMap<>();
        shortDesc.put("text", v.description() != null ? v.description() : v.category());
        rule.put("shortDescription", shortDesc);

        if (v.replacement() != null) {
            Map<String, Object> fullDesc = new LinkedHashMap<>();
            fullDesc.put("text", v.description() + "\n\nFix: " + v.replacement());
            rule.put("fullDescription", fullDesc);
        }

        rule.put("defaultConfiguration", Map.of(
            "level", toSarifLevel(v.severity())
        ));

        // Help text with CWE link
        if (v.cweId() != null) {
            Map<String, Object> help = new LinkedHashMap<>();
            help.put("text", v.cweName() + " (" + v.cweId() + ")");
            help.put("markdown", String.format("[%s — %s](%s)", v.cweId(), v.cweName(), v.cweUrl()));
            rule.put("help", help);

            rule.put("relationships", List.of(Map.of(
                "target", Map.of(
                    "id", v.cweId(),
                    "toolComponent", Map.of("name", "CWE"),
                    "guid", null
                ),
                "kinds", List.of("superset")
            )));

            rule.put("properties", Map.of(
                "tags", List.of("security", "supply-chain", v.cweId()),
                "precision", "medium"
            ));
        }

        return rule;
    }

    private List<Map<String, Object>> buildResults() {
        return violations.stream().map(this::buildResult).toList();
    }

    private Map<String, Object> buildResult(ForbiddenApiScanner.ForbiddenApiViolation v) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ruleId", v.category());
        result.put("level", toSarifLevel(v.severity()));

        Map<String, Object> message = new LinkedHashMap<>();
        String msgText = v.description();
        if (v.replacement() != null) {
            msgText += " Fix: " + v.replacement();
        }
        message.put("text", msgText);
        result.put("message", message);

        result.put("locations", List.of(buildLocation(v)));

        if (v.cweId() != null) {
            result.put("taxa", List.of(Map.of(
                "id", v.cweId(),
                "toolComponent", Map.of("name", "CWE")
            )));
        }

        return result;
    }

    private Map<String, Object> buildLocation(ForbiddenApiScanner.ForbiddenApiViolation v) {
        // Express path relative to project root for portability
        String relPath;
        try {
            relPath = projectRoot.relativize(Path.of(v.filePath())).toString();
        } catch (IllegalArgumentException e) {
            relPath = v.filePath();
        }
        // SARIF URIs use forward slashes
        String sarifUri = "SRCROOT/" + relPath.replace('\\', '/');

        Map<String, Object> region = new LinkedHashMap<>();
        region.put("startLine", v.line());

        Map<String, Object> artifactLocation = new LinkedHashMap<>();
        artifactLocation.put("uri", sarifUri);
        artifactLocation.put("uriBaseId", "SRCROOT");

        Map<String, Object> physicalLocation = new LinkedHashMap<>();
        physicalLocation.put("artifactLocation", artifactLocation);
        physicalLocation.put("region", region);

        return Map.of("physicalLocation", physicalLocation);
    }

    /** Maps dede severity to SARIF level. */
    private String toSarifLevel(ForbiddenApiCatalog.Severity severity) {
        return switch (severity) {
            case CRITICAL -> "error";
            case HIGH     -> "error";
            case MEDIUM   -> "warning";
            case INFO     -> "note";
        };
    }

    /** Converts SCREAMING_SNAKE_CASE category name to PascalCase rule name. */
    private String toRuleName(String category) {
        return Arrays.stream(category.split("_"))
            .map(w -> w.isEmpty() ? w
                : Character.toUpperCase(w.charAt(0)) + w.substring(1).toLowerCase())
            .collect(Collectors.joining());
    }
}
