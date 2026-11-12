package com.dede.cloud;

import com.dede.cloud.CloudFindings.Finding;
import com.dede.security.ServiceUserAnalyzer;
import com.dede.security.ServiceUserAnalyzer.ServiceUserIssue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Opt-in checks ({@code --extended-checks} / {@code dede.extendedChecks}) beyond the default
 * catalog scan and graph checks:
 *
 * <ul>
 *   <li><b>content-package</b>: OakPal validation of built content packages (.zip under
 *       {@code target/}; needs a prior {@code mvn package}). Slow: starts an in-memory Oak.</li>
 *   <li><b>legacy-path</b>: filter.xml roots that deploy to locations AEM as a Cloud Service
 *       manages or forbids, plus references to legacy paths in code and content.</li>
 *   <li><b>service-user</b>: service user mappings that use admin or wildcard bundles.</li>
 * </ul>
 *
 * Opt-in because they are newer to the gate and OakPal is slow; findings use the same
 * {@link Finding} shape, so --fail-on-critical and failOnCritical gate on them when enabled.
 */
@Component
public class ExtendedChecks {

    private static final Logger log = LoggerFactory.getLogger(ExtendedChecks.class);

    public static final String SOURCE_CONTENT_PACKAGE = "content-package";
    public static final String SOURCE_LEGACY_PATH = "legacy-path";
    public static final String SOURCE_SERVICE_USER = "service-user";

    private final OakPalPackageValidator packageValidator;
    private final LegacyPathDetector legacyPathDetector;
    private final ServiceUserAnalyzer serviceUserAnalyzer;

    public ExtendedChecks(OakPalPackageValidator packageValidator, LegacyPathDetector legacyPathDetector,
                          ServiceUserAnalyzer serviceUserAnalyzer) {
        this.packageValidator = packageValidator;
        this.legacyPathDetector = legacyPathDetector;
        this.serviceUserAnalyzer = serviceUserAnalyzer;
    }

    public List<Finding> run(Path projectRoot) {
        List<Finding> findings = new ArrayList<>();
        findings.addAll(legacyPaths(legacyPathDetector.scanProject(projectRoot)));
        findings.addAll(serviceUsers(serviceUserAnalyzer.analyzeServiceUsers()));
        findings.addAll(contentPackages(packageValidator.validateProject(projectRoot)));
        return findings;
    }

    static List<Finding> legacyPaths(LegacyPathDetector.LegacyPathReport report) {
        List<Finding> findings = new ArrayList<>();
        for (LegacyPathDetector.LegacyPathViolation v : report.violations()) {
            String severity;
            if (v.isDeployment()) {
                severity = switch (v.severity()) {
                    case SEVERE -> "CRITICAL";
                    case MAJOR -> "HIGH";
                    case MINOR -> "MEDIUM";
                };
            } else if ("/libs".equals(v.legacyPath())) {
                // Reading /libs (dialog fields, overlays' super types) is normal; only
                // deploying into /libs is forbidden, and that is caught via filter.xml above.
                continue;
            } else {
                severity = "MEDIUM";
            }
            String message = v.isDeployment()
                ? v.description()
                : "References legacy path " + v.foundPath() + ": " + v.description();
            String replacement = v.migrationTarget() != null ? "Move to " + v.migrationTarget() : null;
            findings.add(new Finding(SOURCE_LEGACY_PATH, "LEGACY_PATH", severity, v.filePath(), v.line(),
                message, v.foundPath(), v.sourceType(), replacement, null));
        }
        return findings;
    }

    static List<Finding> serviceUsers(ServiceUserAnalyzer.ServiceUserSummary summary) {
        List<Finding> findings = new ArrayList<>();
        for (ServiceUserIssue issue : summary.issues()) {
            String severity = switch (issue.severity()) {
                case CRITICAL -> "CRITICAL";
                case HIGH -> "HIGH";
                case WARNING -> "MEDIUM";
                // "Bundle not found in project" is usually an external bundle: not actionable.
                case INFO -> null;
            };
            if (severity == null) continue;
            String file = issue.configNode() != null ? issue.configNode().getFilePath() : null;
            findings.add(new Finding(SOURCE_SERVICE_USER, "SERVICE_USER_MAPPING", severity, file, 0,
                issue.title() + ": " + issue.description(), null, null, null, null));
        }
        return findings;
    }

    /**
     * Import errors caused by validating offline: OakPal's in-memory repository has no AEM
     * product node types (cq:Component, sling:Folder, dam:*) and none of the parent nodes other
     * packages or the product install. Stock archetype packages fail this way by the hundred.
     */
    private static final java.util.regex.Pattern OFFLINE_IMPORT_NOISE = java.util.regex.Pattern.compile(
        "NoSuchNodeTypeException|Node type \\S+ ?does not exist|Parent node not found");

    static List<Finding> contentPackages(OakPalPackageValidator.PackageValidationReport report) {
        List<Finding> findings = new ArrayList<>();
        int skippedOfflineNoise = 0;
        for (OakPalPackageValidator.PackageValidationResult result : report.results()) {
            for (OakPalPackageValidator.PackageViolation v : result.violations()) {
                if (v.description() != null && OFFLINE_IMPORT_NOISE.matcher(v.description()).find()) {
                    skippedOfflineNoise++;
                    continue;
                }
                boolean toolFailure = "VALIDATION_ERROR".equals(v.checkName());
                // A package OakPal could not open is a tooling problem, not a CRITICAL code
                // finding -- report it so it is not silently skipped, but don't fail the gate.
                String severity = toolFailure ? "HIGH" : switch (v.severity()) {
                    case SEVERE -> "CRITICAL";
                    case MAJOR -> "HIGH";
                    case MINOR -> "MEDIUM";
                };
                findings.add(new Finding(SOURCE_CONTENT_PACKAGE, "PACKAGE_" + v.checkName(), severity,
                    result.packagePath(), 0, v.description(), v.nodePath(), null, null, null));
            }
        }
        if (skippedOfflineNoise > 0) {
            log.info("Extended checks: skipped {} content-package import error(s) caused by AEM node types "
                + "or parent nodes that are not available to offline validation.", skippedOfflineNoise);
        }
        if (report.results().isEmpty()) {
            log.info("Extended checks: no built content packages found under target/ -- run 'mvn package' "
                + "first to include OakPal content-package validation.");
        }
        return findings;
    }
}
