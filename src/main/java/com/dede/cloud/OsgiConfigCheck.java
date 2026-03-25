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
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.RepositoryException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OakPal ProgressCheck that validates OSGi configurations in content packages.
 *
 * Uses ForbiddenApiCatalog as the source of truth for:
 * - Deprecated OSGi PIDs
 * - Cloud-managed PIDs that cannot be customized
 *
 * Also detects:
 * - Service user mappings with admin user
 * - Missing run mode specificity
 * - Duplicate PID configurations
 * - Sensitive values that should use Cloud Manager secrets
 */
public class OsgiConfigCheck implements ProgressCheck {

    private static final Logger log = LoggerFactory.getLogger(OsgiConfigCheck.class);

    private final ForbiddenApiCatalog catalog;
    private final List<Violation> violations = new ArrayList<>();
    private final Map<String, List<String>> configsByPid = new HashMap<>();

    // Pattern to extract PID from config path
    private static final Pattern CONFIG_PATH_PATTERN = Pattern.compile(
        "/apps/.*/config(?:\\.[\\w.]+)?/([^/]+)(?:\\.cfg\\.json|\\.config|/\\.content\\.xml)$"
    );

    // Factory config pattern
    private static final Pattern FACTORY_PID_PATTERN = Pattern.compile("^(.+)[~-](.+)$");

    // Sensitive property patterns (should use environment variables)
    private static final Set<String> SENSITIVE_PROPERTIES = Set.of(
        "password",
        "secret",
        "apikey",
        "api.key",
        "token",
        "credential",
        "privatekey",
        "private.key"
    );

    /**
     * Creates OsgiConfigCheck without catalog (backwards compatibility).
     */
    public OsgiConfigCheck() {
        this(null);
    }

    /**
     * Creates OsgiConfigCheck with catalog for PID validation.
     */
    public OsgiConfigCheck(ForbiddenApiCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public String getCheckName() {
        return "OsgiConfigCheck";
    }

    @Override
    public void startedScan() {
        violations.clear();
        configsByPid.clear();
    }

    @Override
    public void importedPath(PackageId packageId, String path, Node node,
                             PathAction pathAction) throws RepositoryException {

        // Check if this is an OSGi config path
        Matcher matcher = CONFIG_PATH_PATTERN.matcher(path);
        if (!matcher.find()) {
            return;
        }

        String configFileName = matcher.group(1);
        String pid = extractPid(configFileName);
        String factoryPid = extractFactoryPid(configFileName);

        log.debug("Found OSGi config: {} (PID: {}, Factory: {})", path, pid, factoryPid);

        // Track for duplicate detection
        String effectivePid = factoryPid != null ? factoryPid : pid;
        configsByPid.computeIfAbsent(effectivePid, k -> new ArrayList<>()).add(path);

        // Check against catalog for deprecated and cloud-managed PIDs
        if (catalog != null) {
            // Check deprecated PIDs
            Optional<ForbiddenApiCatalog.DeprecatedOsgiPid> deprecatedPid = catalog.checkDeprecatedPid(pid);
            if (deprecatedPid.isEmpty() && factoryPid != null) {
                deprecatedPid = catalog.checkDeprecatedPid(factoryPid);
            }
            if (deprecatedPid.isPresent()) {
                ForbiddenApiCatalog.DeprecatedOsgiPid dp = deprecatedPid.get();
                violations.add(new SimpleViolation(
                    mapSeverity(dp.severity()),
                    String.format("Deprecated OSGi configuration: %s. %s", path, dp.description()),
                    packageId
                ));
            }

            // Check cloud-managed PIDs
            Optional<ForbiddenApiCatalog.CloudManagedOsgiPid> cloudManagedPid = catalog.checkCloudManagedPid(pid);
            if (cloudManagedPid.isEmpty() && factoryPid != null) {
                cloudManagedPid = catalog.checkCloudManagedPid(factoryPid);
            }
            if (cloudManagedPid.isPresent()) {
                ForbiddenApiCatalog.CloudManagedOsgiPid cp = cloudManagedPid.get();
                violations.add(new SimpleViolation(
                    Severity.SEVERE,
                    String.format("Cloud-managed configuration cannot be customized: %s. %s",
                        path, cp.description()),
                    packageId
                ));
            }
        }

        // Check for service user mappings with dangerous patterns
        if (pid.contains("ServiceUserMapper") || (factoryPid != null && factoryPid.contains("ServiceUserMapper"))) {
            checkServiceUserMapping(packageId, path, node);
        }

        // Check for sensitive values
        checkSensitiveProperties(packageId, path, node);

        // Check for run mode specificity
        checkRunModeSpecificity(packageId, path);
    }

    private String extractPid(String configFileName) {
        // Remove extensions
        String name = configFileName
            .replace(".cfg.json", "")
            .replace(".config", "");

        // Handle factory configs
        Matcher matcher = FACTORY_PID_PATTERN.matcher(name);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        return name;
    }

    private String extractFactoryPid(String configFileName) {
        String name = configFileName
            .replace(".cfg.json", "")
            .replace(".config", "");

        Matcher matcher = FACTORY_PID_PATTERN.matcher(name);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        return null;
    }

    private void checkServiceUserMapping(PackageId packageId, String path, Node node)
            throws RepositoryException {
        // Check for user.mapping property
        if (node.hasProperty("user.mapping")) {
            Property mappingProp = node.getProperty("user.mapping");
            String[] mappings;

            if (mappingProp.isMultiple()) {
                mappings = Arrays.stream(mappingProp.getValues())
                    .map(v -> {
                        try {
                            return v.getString();
                        } catch (RepositoryException e) {
                            return "";
                        }
                    })
                    .toArray(String[]::new);
            } else {
                mappings = new String[]{mappingProp.getString()};
            }

            for (String mapping : mappings) {
                // Check for admin user mapping
                if (mapping.contains("=admin") || mapping.contains("=administrator")) {
                    violations.add(new SimpleViolation(
                        Severity.SEVERE,
                        String.format("Service user mapping to admin user: %s in %s. " +
                            "Create a dedicated service user with minimal required permissions.", mapping, path),
                        packageId
                    ));
                }

                // Check for wildcard mappings
                if (mapping.contains(":*=") || mapping.startsWith("*=")) {
                    violations.add(new SimpleViolation(
                        Severity.MAJOR,
                        String.format("Wildcard service user mapping: %s in %s. " +
                            "Specify explicit subservice names.", mapping, path),
                        packageId
                    ));
                }
            }
        }
    }

    private void checkSensitiveProperties(PackageId packageId, String path, Node node)
            throws RepositoryException {
        PropertyIterator props = node.getProperties();
        while (props.hasNext()) {
            Property prop = props.nextProperty();
            String propName = prop.getName().toLowerCase();

            // Skip JCR system properties
            if (propName.startsWith("jcr:") || propName.startsWith("cq:")) {
                continue;
            }

            // Check if property name suggests sensitive data
            for (String sensitive : SENSITIVE_PROPERTIES) {
                if (propName.contains(sensitive)) {
                    String value = prop.isMultiple() ? "[array]" : prop.getString();

                    // Check if value looks like a hardcoded secret (not a variable reference)
                    if (!value.startsWith("$[") && !value.startsWith("${") &&
                        !value.isEmpty() && !value.equals("[array]")) {
                        violations.add(new SimpleViolation(
                            Severity.MAJOR,
                            String.format("Hardcoded sensitive value in '%s' property at %s. " +
                                "Use Cloud Manager environment variables ($[env:VAR_NAME]) instead.",
                                prop.getName(), path),
                            packageId
                        ));
                    }
                    break;
                }
            }
        }
    }

    private void checkRunModeSpecificity(PackageId packageId, String path) {
        // Check if config is in generic /config/ without run mode
        if (path.matches(".*/config/[^/]+$") && !path.contains("/config.")) {
            violations.add(new SimpleViolation(
                Severity.MINOR,
                String.format("OSGi config without run mode specificity: %s. " +
                    "Consider using config.author, config.publish, or config.prod folders.", path),
                packageId
            ));
        }
    }

    @Override
    public void finishedScan() {
        // Check for duplicate/conflicting configurations
        for (Map.Entry<String, List<String>> entry : configsByPid.entrySet()) {
            if (entry.getValue().size() > 1) {
                // Check if they're in different run mode folders (which is OK)
                boolean samePath = entry.getValue().stream()
                    .map(p -> p.replaceAll("/config\\.[^/]+/", "/config/"))
                    .distinct()
                    .count() < entry.getValue().size();

                if (samePath) {
                    violations.add(new SimpleViolation(
                        Severity.MAJOR,
                        String.format("Potentially conflicting configurations for PID '%s': %s",
                            entry.getKey(), entry.getValue())
                    ));
                }
            }
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
