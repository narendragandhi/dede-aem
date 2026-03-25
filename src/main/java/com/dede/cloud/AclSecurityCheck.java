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
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import java.util.*;

/**
 * OakPal ProgressCheck that validates ACL (Access Control List) configurations
 * in content packages for security issues.
 *
 * Detects:
 * - Overly permissive ACLs (everyone, anonymous)
 * - Dangerous privilege grants (jcr:all, rep:write)
 * - Missing ACL restrictions on sensitive paths
 * - Service user permission issues
 */
public class AclSecurityCheck implements ProgressCheck {

    private static final Logger log = LoggerFactory.getLogger(AclSecurityCheck.class);

    private final List<Violation> violations = new ArrayList<>();
    private final Set<String> aclPaths = new HashSet<>();

    // Dangerous principals that should rarely have permissions
    private static final Set<String> DANGEROUS_PRINCIPALS = Set.of(
        "everyone",
        "anonymous",
        "rep:glob"
    );

    // Dangerous privileges that grant too much access
    private static final Set<String> DANGEROUS_PRIVILEGES = Set.of(
        "jcr:all",
        "rep:write",
        "jcr:modifyAccessControl",
        "jcr:lockManagement",
        "jcr:versionManagement"
    );

    // Sensitive paths that should have restricted ACLs
    private static final Set<String> SENSITIVE_PATHS = Set.of(
        "/content/dam",
        "/conf",
        "/apps",
        "/var"
    );

    @Override
    public String getCheckName() {
        return "AclSecurityCheck";
    }

    @Override
    public void startedScan() {
        violations.clear();
        aclPaths.clear();
    }

    @Override
    public void importedPath(PackageId packageId, String path, Node node,
                             PathAction pathAction) throws RepositoryException {
        // Check for rep:policy nodes (ACL definitions)
        if (path.endsWith("/rep:policy") || path.contains("/rep:policy/")) {
            checkAclNode(packageId, path, node);
        }

        // Check for jcr:primaryType = rep:ACL or rep:ACE
        if (node.hasProperty("jcr:primaryType")) {
            String primaryType = node.getProperty("jcr:primaryType").getString();
            if ("rep:ACL".equals(primaryType) || "rep:ACE".equals(primaryType) ||
                "rep:GrantACE".equals(primaryType) || "rep:DenyACE".equals(primaryType)) {
                checkAclNode(packageId, path, node);
            }
        }
    }

    private void checkAclNode(PackageId packageId, String path, Node node)
            throws RepositoryException {

        // Track ACL paths for coverage analysis
        String parentPath = path.contains("/rep:policy")
            ? path.substring(0, path.indexOf("/rep:policy"))
            : path;
        aclPaths.add(parentPath);

        // Check for rep:principalName (the user/group getting permissions)
        if (node.hasProperty("rep:principalName")) {
            String principal = node.getProperty("rep:principalName").getString();

            if (DANGEROUS_PRINCIPALS.contains(principal.toLowerCase())) {
                // Check what privileges are being granted
                if (node.hasProperty("rep:privileges")) {
                    Property privProp = node.getProperty("rep:privileges");
                    Set<String> privileges = extractPrivileges(privProp);

                    // Check for allow (not deny) entries
                    String primaryType = node.hasProperty("jcr:primaryType")
                        ? node.getProperty("jcr:primaryType").getString()
                        : "";

                    if ("rep:GrantACE".equals(primaryType) || !primaryType.contains("Deny")) {
                        if (privileges.stream().anyMatch(DANGEROUS_PRIVILEGES::contains)) {
                            violations.add(new SimpleViolation(
                                Severity.SEVERE,
                                String.format("Dangerous ACL: '%s' granted %s on %s. " +
                                    "This allows anonymous/everyone access with elevated privileges.",
                                    principal, privileges, parentPath),
                                packageId
                            ));
                        } else {
                            violations.add(new SimpleViolation(
                                Severity.MAJOR,
                                String.format("Permissive ACL: '%s' has access to %s. " +
                                    "Review if this is intended.", principal, parentPath),
                                packageId
                            ));
                        }
                    }
                }
            }
        }

        // Check for dangerous privileges regardless of principal
        if (node.hasProperty("rep:privileges")) {
            Property privProp = node.getProperty("rep:privileges");
            Set<String> privileges = extractPrivileges(privProp);

            if (privileges.contains("jcr:all")) {
                // jcr:all is almost never appropriate
                String principal = node.hasProperty("rep:principalName")
                    ? node.getProperty("rep:principalName").getString()
                    : "unknown";

                violations.add(new SimpleViolation(
                    Severity.MAJOR,
                    String.format("jcr:all privilege granted to '%s' on %s. " +
                        "Consider using more specific privileges.", principal, parentPath),
                    packageId
                ));
            }
        }

        // Check for rep:glob patterns that might be too broad
        if (node.hasProperty("rep:glob")) {
            String glob = node.getProperty("rep:glob").getString();
            if ("*".equals(glob) || "**".equals(glob) || glob.isEmpty()) {
                violations.add(new SimpleViolation(
                    Severity.MINOR,
                    String.format("Broad glob pattern '%s' in ACL at %s. " +
                        "Consider more specific restrictions.", glob, parentPath),
                    packageId
                ));
            }
        }
    }

    private Set<String> extractPrivileges(Property privProp) throws RepositoryException {
        Set<String> privileges = new HashSet<>();
        if (privProp.isMultiple()) {
            for (Value v : privProp.getValues()) {
                privileges.add(v.getString());
            }
        } else {
            privileges.add(privProp.getString());
        }
        return privileges;
    }

    @Override
    public void finishedScan() {
        // Check for missing ACLs on sensitive paths
        for (String sensitivePath : SENSITIVE_PATHS) {
            boolean hasAcl = aclPaths.stream().anyMatch(p -> p.startsWith(sensitivePath));
            if (!hasAcl && !aclPaths.isEmpty()) {
                // Only report if we found some ACLs (package has ACL content)
                violations.add(new SimpleViolation(
                    Severity.MINOR,
                    String.format("No explicit ACL found for sensitive path: %s. " +
                        "Consider adding appropriate access controls.", sensitivePath)
                ));
            }
        }
    }

    @Override
    public Collection<Violation> getReportedViolations() {
        return Collections.unmodifiableList(violations);
    }
}
