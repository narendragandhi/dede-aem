package com.dede.cloud;

import net.adamcin.oakpal.api.PathAction;
import net.adamcin.oakpal.api.ProgressCheck;
import net.adamcin.oakpal.api.Severity;
import net.adamcin.oakpal.api.SimpleViolation;
import net.adamcin.oakpal.api.Violation;
import org.apache.jackrabbit.vault.packaging.PackageId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import java.util.*;

/**
 * OakPal ProgressCheck that validates content packages against
 * AEM Cloud Service path restrictions.
 *
 * Uses ForbiddenApiCatalog as the single source of truth for:
 * - Legacy paths that are forbidden in Cloud Service
 * - Paths requiring migration
 * - Severity levels for each path type
 *
 * Also detects:
 * - Content deletions that may be dangerous
 * - Mutable content in immutable locations
 */
public class CloudServicePathCheck implements ProgressCheck {

    private static final Logger log = LoggerFactory.getLogger(CloudServicePathCheck.class);

    private final ForbiddenApiCatalog catalog;
    private final List<Violation> violations = new ArrayList<>();
    private final Set<String> reportedPaths = new HashSet<>();

    public CloudServicePathCheck(ForbiddenApiCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public String getCheckName() {
        return "CloudServicePathCheck";
    }

    @Override
    public void startedScan() {
        violations.clear();
        reportedPaths.clear();
    }

    @Override
    public void importedPath(PackageId packageId, String path, Node node,
                             PathAction pathAction) throws RepositoryException {
        // Skip if already reported
        if (reportedPaths.contains(path)) {
            return;
        }

        // Check against catalog - this is the single source of truth
        if (catalog != null) {
            Optional<ForbiddenApiCatalog.LegacyPath> legacyPath = catalog.checkPath(path);
            if (legacyPath.isPresent()) {
                reportedPaths.add(path);
                ForbiddenApiCatalog.LegacyPath lp = legacyPath.get();
                Severity severity = mapSeverity(lp.severity());

                String message;
                if (lp.migrationTarget() != null) {
                    message = String.format("Legacy path requires migration: %s -> %s. %s",
                        path, lp.migrationTarget(), lp.description());
                } else {
                    message = String.format("Forbidden path in Cloud Service: %s. %s",
                        path, lp.description());
                }

                violations.add(new SimpleViolation(severity, message, packageId));
                return;
            }
        }

        // Check for mutable content in install folders (OSGi configs)
        if (path.matches("^/apps/.*/install/.*\\.config$")) {
            reportedPaths.add(path);
            violations.add(new SimpleViolation(
                Severity.MINOR,
                String.format("OSGi config in install folder: %s. Consider using " +
                    "config.<runmode> folders for environment-specific configurations.", path),
                packageId
            ));
        }
    }

    @Override
    public void deletedPath(PackageId packageId, String path, Session session)
            throws RepositoryException {
        // Check for dangerous deletions
        if (path.startsWith("/content/") && !path.contains("/test/")) {
            violations.add(new SimpleViolation(
                Severity.MAJOR,
                String.format("Content deletion detected: %s. Verify this is intentional.", path),
                packageId
            ));
        }

        if (path.startsWith("/conf/") || path.startsWith("/apps/")) {
            violations.add(new SimpleViolation(
                Severity.MINOR,
                String.format("Configuration/application deletion: %s", path),
                packageId
            ));
        }
    }

    @Override
    public Collection<Violation> getReportedViolations() {
        return Collections.unmodifiableList(violations);
    }

    /**
     * Maps catalog severity to OakPal severity.
     */
    private Severity mapSeverity(ForbiddenApiCatalog.PathSeverity pathSeverity) {
        if (pathSeverity == null) {
            return Severity.MAJOR;
        }
        return switch (pathSeverity) {
            case SEVERE -> Severity.SEVERE;
            case MAJOR -> Severity.MAJOR;
            case MINOR -> Severity.MINOR;
        };
    }
}
