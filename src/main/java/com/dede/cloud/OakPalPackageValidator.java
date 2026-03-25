package com.dede.cloud;

import com.dede.domain.GraphService;
import com.dede.domain.model.CodeNode;
import com.dede.domain.model.NodeType;
import com.dede.domain.model.RelationshipType;
import net.adamcin.oakpal.api.ProgressCheck;
import net.adamcin.oakpal.api.Severity;
import net.adamcin.oakpal.api.Violation;
import net.adamcin.oakpal.core.CheckReport;
import net.adamcin.oakpal.core.OakMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Validates AEM content packages using OakPal.
 *
 * OakPal simulates package installation in a temporary Oak repository,
 * catching issues that static analysis cannot detect:
 * - NodeType constraint violations
 * - Filter.xml problems
 * - Package structure issues
 * - ACL validation
 * - Content deletion analysis
 *
 * Integrates with the Dede graph by creating CONTENT_PACKAGE and
 * PACKAGE_VIOLATION nodes.
 */
@Component
public class OakPalPackageValidator {

    private static final Logger log = LoggerFactory.getLogger(OakPalPackageValidator.class);

    private final GraphService graphService;
    private final ForbiddenApiCatalog forbiddenApiCatalog;

    public OakPalPackageValidator(GraphService graphService, ForbiddenApiCatalog forbiddenApiCatalog) {
        this.graphService = graphService;
        this.forbiddenApiCatalog = forbiddenApiCatalog;
    }

    /**
     * Validates all content packages found in a project.
     *
     * @param projectRoot The root directory of the AEM project
     * @return Validation report containing all findings
     */
    public PackageValidationReport validateProject(Path projectRoot) {
        log.info("Scanning for content packages in: {}", projectRoot);

        List<Path> packages = findContentPackages(projectRoot);
        log.info("Found {} content packages", packages.size());

        List<PackageValidationResult> results = new ArrayList<>();
        for (Path pkg : packages) {
            try {
                PackageValidationResult result = validatePackage(pkg);
                results.add(result);
                createGraphNodes(result);
            } catch (Exception e) {
                log.error("Failed to validate package: {}", pkg, e);
                results.add(new PackageValidationResult(
                    pkg.toString(),
                    pkg.getFileName().toString(),
                    List.of(new PackageViolation(
                        Severity.SEVERE,
                        "VALIDATION_ERROR",
                        "Failed to validate package: " + e.getMessage(),
                        null,
                        null
                    )),
                    false
                ));
            }
        }

        return new PackageValidationReport(
            projectRoot.toString(),
            results,
            calculateOverallScore(results)
        );
    }

    /**
     * Validates a single content package.
     *
     * @param packagePath Path to the .zip content package
     * @return Validation result with all violations found
     */
    public PackageValidationResult validatePackage(Path packagePath) throws Exception {
        log.info("Validating package: {}", packagePath);

        File packageFile = packagePath.toFile();
        if (!packageFile.exists() || !packageFile.isFile()) {
            throw new IllegalArgumentException("Package file does not exist: " + packagePath);
        }

        List<PackageViolation> violations = new ArrayList<>();

        // Build OakMachine with custom checks
        OakMachine.Builder builder = new OakMachine.Builder();

        // Add custom AEM Cloud Service checks - all use ForbiddenApiCatalog as source of truth
        builder.withProgressCheck(new CloudServicePathCheck(forbiddenApiCatalog));
        builder.withProgressCheck(new AclSecurityCheck());
        builder.withProgressCheck(new OsgiConfigCheck(forbiddenApiCatalog));

        OakMachine machine = builder.build();

        // Execute scan
        List<CheckReport> reports = machine.scanPackage(packageFile);

        // Collect violations from all checks
        for (CheckReport report : reports) {
            for (Violation violation : report.getViolations()) {
                violations.add(new PackageViolation(
                    violation.getSeverity(),
                    report.getCheckName(),
                    violation.getDescription(),
                    extractPackagePath(violation),
                    extractNodePath(violation)
                ));
            }
        }

        boolean isValid = violations.stream()
            .noneMatch(v -> v.severity() == Severity.SEVERE || v.severity() == Severity.MAJOR);

        return new PackageValidationResult(
            packagePath.toString(),
            packageFile.getName(),
            violations,
            isValid
        );
    }

    /**
     * Finds all content packages in the project.
     * Looks for .zip files in target directories of ui.apps, ui.content, etc.
     */
    private List<Path> findContentPackages(Path projectRoot) {
        List<Path> packages = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(projectRoot, 5)) {
            packages = paths
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".zip"))
                .filter(p -> {
                    String pathStr = p.toString();
                    // Look for content packages in target directories
                    return pathStr.contains("/target/") &&
                           (pathStr.contains("ui.apps") ||
                            pathStr.contains("ui.content") ||
                            pathStr.contains("ui.config") ||
                            pathStr.contains("all/") ||
                            pathStr.contains("-content-"));
                })
                .collect(Collectors.toList());
        } catch (IOException e) {
            log.warn("Error searching for packages: {}", e.getMessage());
        }

        return packages;
    }

    /**
     * Creates graph nodes for validated packages and their violations.
     */
    private void createGraphNodes(PackageValidationResult result) {
        // Create CONTENT_PACKAGE node
        String packageId = "pkg:" + sanitizeForId(result.packageName());
        CodeNode packageNode = new CodeNode(
            packageId,
            result.packageName(),
            NodeType.CONTENT_PACKAGE,
            "AEM Content Package",
            result.packagePath()
        );
        packageNode.getProperties().put("valid", String.valueOf(result.isValid()));
        packageNode.getProperties().put("violationCount", String.valueOf(result.violations().size()));

        graphService.addNode(packageNode);

        // Create PACKAGE_VIOLATION nodes for each violation
        int violationIndex = 0;
        for (PackageViolation violation : result.violations()) {
            String violationId = String.format("pkg-violation:%s:%d",
                sanitizeForId(result.packageName()), violationIndex++);

            CodeNode violationNode = new CodeNode(
                violationId,
                violation.checkName() + ": " + truncate(violation.description(), 50),
                NodeType.PACKAGE_VIOLATION,
                violation.description(),
                violation.nodePath()
            );
            violationNode.getProperties().put("severity", violation.severity().name());
            violationNode.getProperties().put("checkName", violation.checkName());
            if (violation.nodePath() != null) {
                violationNode.getProperties().put("nodePath", violation.nodePath());
            }

            graphService.addNode(violationNode);

            // Link violation to package
            graphService.addEdge(violationNode, packageNode, RelationshipType.VIOLATES);
        }
    }

    private String extractPackagePath(Violation violation) {
        // Try to extract package identifier from violation
        if (violation.getPackages() != null && !violation.getPackages().isEmpty()) {
            return violation.getPackages().stream()
                .map(p -> p.toString())
                .collect(Collectors.joining(", "));
        }
        return null;
    }

    private String extractNodePath(Violation violation) {
        // The description often contains the path
        String desc = violation.getDescription();
        if (desc != null && desc.contains("/")) {
            // Try to extract path from description
            int start = desc.indexOf('/');
            int end = desc.indexOf(' ', start);
            if (end == -1) end = desc.length();
            return desc.substring(start, Math.min(end, desc.length()));
        }
        return null;
    }

    private int calculateOverallScore(List<PackageValidationResult> results) {
        if (results.isEmpty()) return 100;

        int totalViolations = 0;
        int severeCount = 0;
        int majorCount = 0;

        for (PackageValidationResult result : results) {
            for (PackageViolation v : result.violations()) {
                totalViolations++;
                if (v.severity() == Severity.SEVERE) severeCount++;
                if (v.severity() == Severity.MAJOR) majorCount++;
            }
        }

        // Score calculation: start at 100, deduct for violations
        int score = 100;
        score -= severeCount * 20;  // -20 per severe
        score -= majorCount * 10;   // -10 per major
        score -= (totalViolations - severeCount - majorCount) * 2;  // -2 per minor

        return Math.max(0, score);
    }

    private String sanitizeForId(String input) {
        return input.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }

    // --- Record Types ---

    public record PackageValidationReport(
        String projectPath,
        List<PackageValidationResult> results,
        int overallScore
    ) {
        public int getTotalPackages() {
            return results.size();
        }

        public int getValidPackages() {
            return (int) results.stream().filter(PackageValidationResult::isValid).count();
        }

        public int getTotalViolations() {
            return results.stream()
                .mapToInt(r -> r.violations().size())
                .sum();
        }

        public Map<Severity, Long> getViolationsBySeverity() {
            return results.stream()
                .flatMap(r -> r.violations().stream())
                .collect(Collectors.groupingBy(PackageViolation::severity, Collectors.counting()));
        }
    }

    public record PackageValidationResult(
        String packagePath,
        String packageName,
        List<PackageViolation> violations,
        boolean isValid
    ) {
        public int getSevereCount() {
            return (int) violations.stream()
                .filter(v -> v.severity() == Severity.SEVERE)
                .count();
        }

        public int getMajorCount() {
            return (int) violations.stream()
                .filter(v -> v.severity() == Severity.MAJOR)
                .count();
        }
    }

    public record PackageViolation(
        Severity severity,
        String checkName,
        String description,
        String packageId,
        String nodePath
    ) {}
}
