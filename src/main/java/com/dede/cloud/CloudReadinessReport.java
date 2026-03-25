package com.dede.cloud;

import com.dede.security.ServiceUserAnalyzer;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates comprehensive cloud readiness reports for AEM projects.
 *
 * Combines results from:
 * - ForbiddenApiScanner
 * - LegacyPathDetector
 * - ServiceUserAnalyzer
 * - OakPalPackageValidator (content package validation)
 *
 * Outputs reports in JSON and Markdown formats.
 */
@Component
public class CloudReadinessReport {

    private static final Logger log = LoggerFactory.getLogger(CloudReadinessReport.class);

    private final ForbiddenApiScanner forbiddenApiScanner;
    private final LegacyPathDetector legacyPathDetector;
    private final ServiceUserAnalyzer serviceUserAnalyzer;
    private final OakPalPackageValidator oakPalValidator;
    private final ObjectMapper objectMapper;

    public CloudReadinessReport(ForbiddenApiScanner forbiddenApiScanner,
                                 LegacyPathDetector legacyPathDetector,
                                 ServiceUserAnalyzer serviceUserAnalyzer,
                                 OakPalPackageValidator oakPalValidator) {
        this.forbiddenApiScanner = forbiddenApiScanner;
        this.legacyPathDetector = legacyPathDetector;
        this.serviceUserAnalyzer = serviceUserAnalyzer;
        this.oakPalValidator = oakPalValidator;
        this.objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    /**
     * Generates a complete cloud readiness report for a project.
     */
    public Report generateReport(Path projectRoot) {
        return generateReport(projectRoot, true);
    }

    /**
     * Generates a cloud readiness report with optional package validation.
     *
     * @param projectRoot The project root path
     * @param includePackageValidation Whether to run OakPal package validation
     * @return The complete report
     */
    public Report generateReport(Path projectRoot, boolean includePackageValidation) {
        log.info("Generating cloud readiness report for: {}", projectRoot);

        // Run all scans
        ForbiddenApiScanner.ScanResult forbiddenApiResult = forbiddenApiScanner.scanProject(projectRoot);
        LegacyPathDetector.LegacyPathReport legacyPathResult = legacyPathDetector.scanProject(projectRoot);
        List<ForbiddenApiScanner.AdminSessionViolation> adminSessions =
            forbiddenApiScanner.scanForAdminSessions(projectRoot);
        ServiceUserAnalyzer.ServiceUserSummary serviceUserSummary = serviceUserAnalyzer.analyzeServiceUsers();

        // Run OakPal package validation if requested
        OakPalPackageValidator.PackageValidationReport packageValidation = null;
        if (includePackageValidation) {
            try {
                packageValidation = oakPalValidator.validateProject(projectRoot);
                log.info("OakPal validation complete: {} packages, {} violations",
                    packageValidation.getTotalPackages(), packageValidation.getTotalViolations());
            } catch (Exception e) {
                log.warn("OakPal validation failed, continuing without package validation: {}", e.getMessage());
            }
        }

        // Calculate overall score (including package validation)
        int score = calculateReadinessScore(forbiddenApiResult, legacyPathResult,
            adminSessions, serviceUserSummary, packageValidation);

        // Generate summary
        ReportSummary summary = new ReportSummary(
            score,
            getReadinessLevel(score),
            forbiddenApiResult.getTotalViolations(),
            legacyPathResult.getTotalViolations(),
            adminSessions.size(),
            serviceUserSummary.getCriticalIssueCount(),
            packageValidation != null ? packageValidation.getTotalViolations() : 0,
            packageValidation != null ? packageValidation.getTotalPackages() : 0
        );

        // Compile issues by priority
        List<Issue> criticalIssues = compileCriticalIssues(forbiddenApiResult, adminSessions, packageValidation);
        List<Issue> highIssues = compileHighIssues(forbiddenApiResult, legacyPathResult, packageValidation);
        List<Issue> mediumIssues = compileMediumIssues(forbiddenApiResult, packageValidation);

        // Get migration recommendations
        List<LegacyPathDetector.MigrationRecommendation> migrations =
            legacyPathDetector.getMigrationRecommendations(legacyPathResult);

        return new Report(
            LocalDateTime.now(),
            projectRoot.toString(),
            summary,
            criticalIssues,
            highIssues,
            mediumIssues,
            migrations,
            serviceUserSummary,
            packageValidation,
            calculateEstimatedEffort(forbiddenApiResult, legacyPathResult, adminSessions, packageValidation)
        );
    }

    /**
     * Calculates a cloud readiness score (0-100).
     */
    private int calculateReadinessScore(ForbiddenApiScanner.ScanResult forbiddenApiResult,
                                         LegacyPathDetector.LegacyPathReport legacyPathResult,
                                         List<ForbiddenApiScanner.AdminSessionViolation> adminSessions,
                                         ServiceUserAnalyzer.ServiceUserSummary serviceUserSummary,
                                         OakPalPackageValidator.PackageValidationReport packageValidation) {
        int score = 100;

        // Critical issues: -20 points each (max -60)
        int criticalCount = forbiddenApiResult.getCriticalCount() + adminSessions.size() +
            serviceUserSummary.getCriticalIssueCount();
        score -= Math.min(60, criticalCount * 20);

        // High issues: -5 points each (max -25)
        int highCount = forbiddenApiResult.getHighCount();
        score -= Math.min(25, highCount * 5);

        // Legacy paths: -2 points each (max -10)
        int legacyPathCount = legacyPathResult.getUniqueLegacyPaths().size();
        score -= Math.min(10, legacyPathCount * 2);

        // Missing service users: -3 points each (max -5)
        int missingServiceUsers = serviceUserSummary.getIssueCount() -
            serviceUserSummary.getCriticalIssueCount();
        score -= Math.min(5, missingServiceUsers * 3);

        // Package validation issues
        if (packageValidation != null) {
            Map<net.adamcin.oakpal.api.Severity, Long> pkgViolations = packageValidation.getViolationsBySeverity();
            int severeCount = pkgViolations.getOrDefault(net.adamcin.oakpal.api.Severity.SEVERE, 0L).intValue();
            int majorCount = pkgViolations.getOrDefault(net.adamcin.oakpal.api.Severity.MAJOR, 0L).intValue();

            // Severe package violations: -15 points each (max -30)
            score -= Math.min(30, severeCount * 15);

            // Major package violations: -5 points each (max -15)
            score -= Math.min(15, majorCount * 5);
        }

        return Math.max(0, score);
    }

    /**
     * Gets the readiness level based on score.
     */
    private ReadinessLevel getReadinessLevel(int score) {
        if (score >= 90) return ReadinessLevel.READY;
        if (score >= 70) return ReadinessLevel.MINOR_CHANGES;
        if (score >= 50) return ReadinessLevel.MODERATE_CHANGES;
        if (score >= 30) return ReadinessLevel.SIGNIFICANT_CHANGES;
        return ReadinessLevel.MAJOR_REFACTORING;
    }

    /**
     * Compiles critical issues.
     */
    private List<Issue> compileCriticalIssues(ForbiddenApiScanner.ScanResult forbiddenApiResult,
                                               List<ForbiddenApiScanner.AdminSessionViolation> adminSessions,
                                               OakPalPackageValidator.PackageValidationReport packageValidation) {
        List<Issue> issues = new ArrayList<>();

        // Forbidden API critical violations
        forbiddenApiResult.getViolationsBySeverity(ForbiddenApiCatalog.Severity.CRITICAL)
            .forEach(v -> issues.add(new Issue(
                IssueSeverity.CRITICAL,
                v.category(),
                v.description(),
                v.filePath() + ":" + v.line(),
                v.replacement()
            )));

        // Admin session violations
        adminSessions.forEach(v -> issues.add(new Issue(
            IssueSeverity.CRITICAL,
            "ADMIN_SESSION",
            "Usage of " + v.methodName() + " bypasses security controls",
            v.filePath() + ":" + v.line(),
            v.suggestedFix()
        )));

        // Package validation severe violations
        if (packageValidation != null) {
            for (OakPalPackageValidator.PackageValidationResult result : packageValidation.results()) {
                for (OakPalPackageValidator.PackageViolation v : result.violations()) {
                    if (v.severity() == net.adamcin.oakpal.api.Severity.SEVERE) {
                        issues.add(new Issue(
                            IssueSeverity.CRITICAL,
                            "PACKAGE_" + v.checkName(),
                            v.description(),
                            result.packageName() + (v.nodePath() != null ? ":" + v.nodePath() : ""),
                            null
                        ));
                    }
                }
            }
        }

        return issues;
    }

    /**
     * Compiles high severity issues.
     */
    private List<Issue> compileHighIssues(ForbiddenApiScanner.ScanResult forbiddenApiResult,
                                           LegacyPathDetector.LegacyPathReport legacyPathResult,
                                           OakPalPackageValidator.PackageValidationReport packageValidation) {
        List<Issue> issues = new ArrayList<>();

        // Forbidden API high violations
        forbiddenApiResult.getViolationsBySeverity(ForbiddenApiCatalog.Severity.HIGH)
            .forEach(v -> issues.add(new Issue(
                IssueSeverity.HIGH,
                v.category(),
                v.description(),
                v.filePath() + ":" + v.line(),
                v.replacement()
            )));

        // Legacy path violations
        legacyPathResult.violations().forEach(v -> issues.add(new Issue(
            IssueSeverity.HIGH,
            "LEGACY_PATH",
            v.description(),
            v.filePath() + ":" + v.line(),
            v.migrationTarget() != null ?
                "Move to: " + v.migrationTarget() :
                "Path is forbidden in Cloud Service"
        )));

        // Package validation major violations
        if (packageValidation != null) {
            for (OakPalPackageValidator.PackageValidationResult result : packageValidation.results()) {
                for (OakPalPackageValidator.PackageViolation v : result.violations()) {
                    if (v.severity() == net.adamcin.oakpal.api.Severity.MAJOR) {
                        issues.add(new Issue(
                            IssueSeverity.HIGH,
                            "PACKAGE_" + v.checkName(),
                            v.description(),
                            result.packageName() + (v.nodePath() != null ? ":" + v.nodePath() : ""),
                            null
                        ));
                    }
                }
            }
        }

        return issues;
    }

    /**
     * Compiles medium severity issues.
     */
    private List<Issue> compileMediumIssues(ForbiddenApiScanner.ScanResult forbiddenApiResult,
                                             OakPalPackageValidator.PackageValidationReport packageValidation) {
        List<Issue> issues = forbiddenApiResult.getViolationsBySeverity(ForbiddenApiCatalog.Severity.MEDIUM)
            .stream()
            .map(v -> new Issue(
                IssueSeverity.MEDIUM,
                v.category(),
                v.description(),
                v.filePath() + ":" + v.line(),
                v.replacement()
            ))
            .collect(Collectors.toList());

        // Package validation minor violations
        if (packageValidation != null) {
            for (OakPalPackageValidator.PackageValidationResult result : packageValidation.results()) {
                for (OakPalPackageValidator.PackageViolation v : result.violations()) {
                    if (v.severity() == net.adamcin.oakpal.api.Severity.MINOR) {
                        issues.add(new Issue(
                            IssueSeverity.MEDIUM,
                            "PACKAGE_" + v.checkName(),
                            v.description(),
                            result.packageName() + (v.nodePath() != null ? ":" + v.nodePath() : ""),
                            null
                        ));
                    }
                }
            }
        }

        return issues;
    }

    /**
     * Calculates estimated migration effort.
     */
    private EstimatedEffort calculateEstimatedEffort(ForbiddenApiScanner.ScanResult forbiddenApiResult,
                                                      LegacyPathDetector.LegacyPathReport legacyPathResult,
                                                      List<ForbiddenApiScanner.AdminSessionViolation> adminSessions,
                                                      OakPalPackageValidator.PackageValidationReport packageValidation) {
        // Simple heuristic-based estimation
        int criticalHours = (forbiddenApiResult.getCriticalCount() + adminSessions.size()) * 4;
        int highHours = forbiddenApiResult.getHighCount() * 2;
        int legacyPathHours = legacyPathResult.getUniqueLegacyPaths().size() * 2;

        // Add package validation effort
        int packageHours = 0;
        if (packageValidation != null) {
            Map<net.adamcin.oakpal.api.Severity, Long> pkgViolations = packageValidation.getViolationsBySeverity();
            int severeCount = pkgViolations.getOrDefault(net.adamcin.oakpal.api.Severity.SEVERE, 0L).intValue();
            int majorCount = pkgViolations.getOrDefault(net.adamcin.oakpal.api.Severity.MAJOR, 0L).intValue();
            packageHours = severeCount * 3 + majorCount * 1;
        }

        int testingHours = (criticalHours + highHours + legacyPathHours + packageHours) / 2;

        int totalHours = criticalHours + highHours + legacyPathHours + packageHours + testingHours;

        return new EstimatedEffort(
            criticalHours,
            highHours,
            legacyPathHours,
            packageHours,
            testingHours,
            totalHours,
            getEffortCategory(totalHours)
        );
    }

    private String getEffortCategory(int totalHours) {
        if (totalHours <= 8) return "Small (1 day)";
        if (totalHours <= 40) return "Medium (1 week)";
        if (totalHours <= 160) return "Large (1 month)";
        return "Very Large (multiple months)";
    }

    /**
     * Exports report to JSON.
     */
    public String exportToJson(Report report) {
        try {
            return objectMapper.writeValueAsString(report);
        } catch (JsonProcessingException e) {
            log.error("Failed to export report to JSON", e);
            return "{}";
        }
    }

    /**
     * Exports report to Markdown.
     */
    public String exportToMarkdown(Report report) {
        StringBuilder md = new StringBuilder();

        // Header
        md.append("# AEM Cloud Readiness Report\n\n");
        md.append("**Generated:** ").append(
            report.generatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\n");
        md.append("**Project:** ").append(report.projectPath()).append("\n\n");

        // Summary
        md.append("## Summary\n\n");
        md.append("| Metric | Value |\n");
        md.append("|--------|-------|\n");
        md.append("| **Readiness Score** | ").append(report.summary().score()).append("/100 |\n");
        md.append("| **Readiness Level** | ").append(report.summary().level()).append(" |\n");
        md.append("| **Forbidden API Violations** | ").append(report.summary().forbiddenApiViolations()).append(" |\n");
        md.append("| **Legacy Path Violations** | ").append(report.summary().legacyPathViolations()).append(" |\n");
        md.append("| **Admin Session Usages** | ").append(report.summary().adminSessionUsages()).append(" |\n");
        md.append("| **Package Violations** | ").append(report.summary().packageViolations()).append(" |\n");
        md.append("| **Packages Validated** | ").append(report.summary().packagesValidated()).append(" |\n");
        md.append("\n");

        // Estimated Effort
        md.append("## Estimated Migration Effort\n\n");
        md.append("| Category | Hours |\n");
        md.append("|----------|-------|\n");
        md.append("| Critical Issues | ").append(report.estimatedEffort().criticalIssueHours()).append(" |\n");
        md.append("| High Issues | ").append(report.estimatedEffort().highIssueHours()).append(" |\n");
        md.append("| Legacy Path Migration | ").append(report.estimatedEffort().legacyPathMigrationHours()).append(" |\n");
        md.append("| Package Issues | ").append(report.estimatedEffort().packageIssueHours()).append(" |\n");
        md.append("| Testing | ").append(report.estimatedEffort().testingHours()).append(" |\n");
        md.append("| **Total** | **").append(report.estimatedEffort().totalHours()).append("** |\n");
        md.append("| **Category** | ").append(report.estimatedEffort().category()).append(" |\n");
        md.append("\n");

        // Critical Issues
        if (!report.criticalIssues().isEmpty()) {
            md.append("## Critical Issues (Must Fix)\n\n");
            for (Issue issue : report.criticalIssues()) {
                md.append("### ").append(issue.category()).append("\n\n");
                md.append("- **Location:** `").append(issue.location()).append("`\n");
                md.append("- **Description:** ").append(issue.description()).append("\n");
                if (issue.fix() != null) {
                    md.append("- **Fix:** ").append(issue.fix().split("\n")[0]).append("\n");
                }
                md.append("\n");
            }
        }

        // High Issues
        if (!report.highIssues().isEmpty()) {
            md.append("## High Priority Issues\n\n");
            Map<String, List<Issue>> byCategory = report.highIssues().stream()
                .collect(Collectors.groupingBy(Issue::category));

            for (Map.Entry<String, List<Issue>> entry : byCategory.entrySet()) {
                md.append("### ").append(entry.getKey()).append(" (").append(entry.getValue().size()).append(" issues)\n\n");
                for (Issue issue : entry.getValue().stream().limit(5).toList()) {
                    md.append("- `").append(issue.location()).append("`: ").append(issue.description()).append("\n");
                }
                if (entry.getValue().size() > 5) {
                    md.append("- ... and ").append(entry.getValue().size() - 5).append(" more\n");
                }
                md.append("\n");
            }
        }

        // Migration Recommendations
        if (!report.migrationRecommendations().isEmpty()) {
            md.append("## Migration Recommendations\n\n");
            for (LegacyPathDetector.MigrationRecommendation rec : report.migrationRecommendations()) {
                md.append("### ").append(rec.legacyPath()).append("\n\n");
                md.append("- **Description:** ").append(rec.description()).append("\n");
                if (rec.migrationTarget() != null) {
                    md.append("- **Migrate to:** `").append(rec.migrationTarget()).append("`\n");
                } else {
                    md.append("- **Action:** Remove or use alternative approach\n");
                }
                md.append("- **Affected files:** ").append(rec.affectedFiles().size()).append("\n");
                md.append("\n");
            }
        }

        // Service Users
        md.append("## Service User Configuration\n\n");
        md.append("- **Total Mappings:** ").append(report.serviceUserSummary().getMappingCount()).append("\n");
        md.append("- **Unique Service Users:** ").append(report.serviceUserSummary().getUniqueServiceUsers().size()).append("\n");
        md.append("- **Issues:** ").append(report.serviceUserSummary().getIssueCount()).append("\n");
        md.append("\n");

        // Package Validation Results
        if (report.packageValidation() != null && report.packageValidation().getTotalPackages() > 0) {
            md.append("## Content Package Validation (OakPal)\n\n");
            md.append("| Package | Status | Violations |\n");
            md.append("|---------|--------|------------|\n");

            for (OakPalPackageValidator.PackageValidationResult pkg : report.packageValidation().results()) {
                String status = pkg.isValid() ? "Valid" : "Invalid";
                md.append("| ").append(pkg.packageName())
                  .append(" | ").append(status)
                  .append(" | ").append(pkg.violations().size())
                  .append(" |\n");
            }
            md.append("\n");

            // List package violations by severity
            boolean hasPackageIssues = report.packageValidation().getTotalViolations() > 0;
            if (hasPackageIssues) {
                md.append("### Package Violation Details\n\n");
                for (OakPalPackageValidator.PackageValidationResult pkg : report.packageValidation().results()) {
                    if (!pkg.violations().isEmpty()) {
                        md.append("#### ").append(pkg.packageName()).append("\n\n");
                        for (OakPalPackageValidator.PackageViolation v : pkg.violations()) {
                            String severity = v.severity().name().toLowerCase();
                            md.append("- [").append(severity).append("] **").append(v.checkName()).append("**: ");
                            md.append(v.description());
                            if (v.nodePath() != null) {
                                md.append(" (`").append(v.nodePath()).append("`)");
                            }
                            md.append("\n");
                        }
                        md.append("\n");
                    }
                }
            }
        }

        return md.toString();
    }

    // --- Record Types ---

    public enum ReadinessLevel {
        READY("Ready for Cloud Service"),
        MINOR_CHANGES("Minor changes required"),
        MODERATE_CHANGES("Moderate changes required"),
        SIGNIFICANT_CHANGES("Significant changes required"),
        MAJOR_REFACTORING("Major refactoring required");

        private final String description;

        ReadinessLevel(String description) {
            this.description = description;
        }

        @Override
        public String toString() {
            return description;
        }
    }

    public enum IssueSeverity {
        CRITICAL, HIGH, MEDIUM, LOW
    }

    public record Report(
        LocalDateTime generatedAt,
        String projectPath,
        ReportSummary summary,
        List<Issue> criticalIssues,
        List<Issue> highIssues,
        List<Issue> mediumIssues,
        List<LegacyPathDetector.MigrationRecommendation> migrationRecommendations,
        ServiceUserAnalyzer.ServiceUserSummary serviceUserSummary,
        OakPalPackageValidator.PackageValidationReport packageValidation,
        EstimatedEffort estimatedEffort
    ) {}

    public record ReportSummary(
        int score,
        ReadinessLevel level,
        int forbiddenApiViolations,
        int legacyPathViolations,
        int adminSessionUsages,
        int serviceUserCriticalIssues,
        int packageViolations,
        int packagesValidated
    ) {}

    public record Issue(
        IssueSeverity severity,
        String category,
        String description,
        String location,
        String fix
    ) {}

    public record EstimatedEffort(
        int criticalIssueHours,
        int highIssueHours,
        int legacyPathMigrationHours,
        int packageIssueHours,
        int testingHours,
        int totalHours,
        String category
    ) {}
}
