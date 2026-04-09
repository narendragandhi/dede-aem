package com.dede.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Analyzes JCR ACL definitions in content packages for security risks.
 *
 * <p>Scans {@code .content.xml} files for {@code rep:policy} nodes and flags:
 * <ul>
 *   <li>{@code everyone} principal with allow grants — public read/write access</li>
 *   <li>{@code jcr:all} privilege — grants every possible permission</li>
 *   <li>Write/delete grants on sensitive paths ({@code /apps}, {@code /libs}, {@code /etc})</li>
 *   <li>Missing deny rules for anonymous access on non-public content</li>
 * </ul>
 */
@Component
public class AclAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(AclAnalyzer.class);

    private static final Set<String> DANGEROUS_PRIVILEGES = Set.of(
        "jcr:all", "jcr:write", "jcr:modifyProperties",
        "jcr:addChildNodes", "jcr:removeNode", "crx:replicate"
    );

    private static final Set<String> SENSITIVE_PATH_PREFIXES = Set.of(
        "/apps", "/libs", "/etc", "/system", "/bin"
    );

    private static final Set<String> PUBLIC_PRINCIPALS = Set.of(
        "everyone", "anonymous"
    );

    private final DocumentBuilderFactory docBuilderFactory;

    public AclAnalyzer() {
        this.docBuilderFactory = DocumentBuilderFactory.newInstance();
        // Harden against XXE
        try {
            docBuilderFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            docBuilderFactory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            docBuilderFactory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (Exception e) {
            log.warn("Could not configure XXE hardening on DocumentBuilderFactory", e);
        }
        docBuilderFactory.setExpandEntityReferences(false);
        docBuilderFactory.setNamespaceAware(true);
    }

    public AnalysisResult analyze(Path projectRoot) {
        List<AclFinding> findings = new ArrayList<>();
        if (!Files.exists(projectRoot)) return new AnalysisResult(findings);

        try (Stream<Path> paths = Files.walk(projectRoot)) {
            paths.filter(Files::isRegularFile)
                 .filter(p -> p.getFileName().toString().equals(".content.xml"))
                 .forEach(xmlFile -> findings.addAll(analyzeFile(xmlFile, projectRoot)));
        } catch (IOException e) {
            log.error("Error walking project for ACL analysis: {}", projectRoot, e);
        }

        log.info("ACL analysis complete: {} findings", findings.size());
        return new AnalysisResult(findings);
    }

    private List<AclFinding> analyzeFile(Path xmlFile, Path projectRoot) {
        List<AclFinding> findings = new ArrayList<>();
        String relPath;
        try {
            relPath = projectRoot.relativize(xmlFile).toString();
        } catch (IllegalArgumentException e) {
            relPath = xmlFile.toString();
        }

        // Derive the JCR path from the file system location
        // e.g. jcr_root/content/mysite/.content.xml → /content/mysite
        String jcrPath = deriveJcrPath(xmlFile);

        Document doc;
        try {
            doc = docBuilderFactory.newDocumentBuilder().parse(xmlFile.toFile());
        } catch (Exception e) {
            log.debug("Could not parse XML: {}", xmlFile, e);
            return findings;
        }

        // Find all rep:policy elements
        NodeList policies = doc.getElementsByTagNameNS("*", "policy");
        for (int i = 0; i < policies.getLength(); i++) {
            if (policies.item(i) instanceof Element policy) {
                findings.addAll(analyzePolicy(policy, relPath, jcrPath));
            }
        }

        // Also find direct ACE elements (sometimes policy is inline)
        for (String aceType : List.of("GrantACE", "AllowACE", "DenyACE")) {
            NodeList aces = doc.getElementsByTagNameNS("*", aceType);
            for (int i = 0; i < aces.getLength(); i++) {
                if (aces.item(i) instanceof Element ace) {
                    findings.addAll(analyzeAce(ace, relPath, jcrPath, false));
                }
            }
        }

        return findings;
    }

    private List<AclFinding> analyzePolicy(Element policy, String filePath, String jcrPath) {
        List<AclFinding> findings = new ArrayList<>();
        org.w3c.dom.NodeList children = policy.getChildNodes();
        boolean hasDenyForEveryone = false;

        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element ace) {
                String tagLocal = ace.getLocalName() != null ? ace.getLocalName() : ace.getTagName();
                boolean isDeny = tagLocal.toLowerCase().contains("deny");
                findings.addAll(analyzeAce(ace, filePath, jcrPath, isDeny));

                if (isDeny) {
                    String principal = ace.getAttribute("rep:principalName");
                    if (principal.isEmpty()) principal = ace.getAttributeNS("*", "principalName");
                    if (PUBLIC_PRINCIPALS.contains(principal.toLowerCase())) {
                        hasDenyForEveryone = true;
                    }
                }
            }
        }

        // Flag sensitive paths that grant to everyone without a compensating deny
        if (!hasDenyForEveryone && isSensitivePath(jcrPath)) {
            findings.add(new AclFinding(
                filePath, jcrPath, Severity.MEDIUM,
                FindingType.MISSING_DENY_ON_SENSITIVE_PATH,
                String.format("rep:policy on sensitive path '%s' has no deny rule for 'everyone'.", jcrPath),
                "Add a rep:DenyACE for principal 'everyone' to prevent anonymous access."
            ));
        }

        return findings;
    }

    private List<AclFinding> analyzeAce(Element ace, String filePath,
                                         String jcrPath, boolean isDeny) {
        List<AclFinding> findings = new ArrayList<>();
        if (isDeny) return findings; // deny rules are generally safe

        // Extract principal name (handles both namespaced and plain attributes)
        String principal = ace.getAttribute("rep:principalName");
        if (principal.isEmpty()) principal = getAttrIgnoreNs(ace, "principalName");

        // Extract privileges
        String privAttr = ace.getAttribute("rep:privileges");
        if (privAttr.isEmpty()) privAttr = getAttrIgnoreNs(ace, "privileges");

        if (principal.isEmpty() && privAttr.isEmpty()) return findings;

        boolean isPublicPrincipal = PUBLIC_PRINCIPALS.contains(principal.toLowerCase());
        Set<String> privileges = parsePrivileges(privAttr);
        boolean hasDangerousPriv = privileges.stream().anyMatch(DANGEROUS_PRIVILEGES::contains);
        boolean isSensitive = isSensitivePath(jcrPath);

        // everyone/anonymous with any allow grant
        if (isPublicPrincipal && !privileges.isEmpty()) {
            Severity sev = hasDangerousPriv ? Severity.CRITICAL : Severity.HIGH;
            findings.add(new AclFinding(
                filePath, jcrPath, sev,
                FindingType.PUBLIC_PRINCIPAL_GRANT,
                String.format("Principal '%s' granted %s on '%s'. " +
                    "This allows unauthenticated access.", principal, privileges, jcrPath),
                "Restrict this grant to a specific service user or authenticated group. " +
                "If anonymous read is intentional, document it explicitly."
            ));
        }

        // jcr:all anywhere
        if (privileges.contains("jcr:all")) {
            findings.add(new AclFinding(
                filePath, jcrPath, Severity.HIGH,
                FindingType.JCR_ALL_PRIVILEGE,
                String.format("Principal '%s' granted jcr:all on '%s'. " +
                    "This is the broadest possible permission.", principal, jcrPath),
                "Replace jcr:all with the minimum required privileges."
            ));
        }

        // Write/delete on sensitive paths
        if (isSensitive && hasDangerousPriv && !isPublicPrincipal) {
            findings.add(new AclFinding(
                filePath, jcrPath, Severity.MEDIUM,
                FindingType.WRITE_ON_SENSITIVE_PATH,
                String.format("Principal '%s' granted write/delete privilege on sensitive path '%s'.",
                    principal, jcrPath),
                "Verify this grant is intentional and uses the principle of least privilege."
            ));
        }

        return findings;
    }

    private Set<String> parsePrivileges(String privAttr) {
        if (privAttr == null || privAttr.isBlank()) return Set.of();
        // Handles [jcr:read, jcr:write] and bare jcr:read formats
        return Arrays.stream(privAttr
                .replace("[", "").replace("]", "")
                .split("[,\\s]+"))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(java.util.stream.Collectors.toSet());
    }

    private boolean isSensitivePath(String jcrPath) {
        if (jcrPath == null) return false;
        return SENSITIVE_PATH_PREFIXES.stream().anyMatch(jcrPath::startsWith);
    }

    private String deriveJcrPath(Path xmlFile) {
        String path = xmlFile.toString().replace('\\', '/');
        int jcrRootIdx = path.indexOf("jcr_root");
        if (jcrRootIdx >= 0) {
            String rel = path.substring(jcrRootIdx + "jcr_root".length());
            // Strip /.content.xml suffix
            rel = rel.replace("/.content.xml", "").replace(".content.xml", "");
            return rel.isEmpty() ? "/" : rel;
        }
        // Fallback: use parent dir name
        return "/" + xmlFile.getParent().getFileName().toString();
    }

    private String getAttrIgnoreNs(Element el, String localName) {
        org.w3c.dom.NamedNodeMap attrs = el.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            org.w3c.dom.Node attr = attrs.item(i);
            if (localName.equals(attr.getLocalName())) return attr.getNodeValue();
        }
        return "";
    }

    // --- Types ---

    public enum Severity { CRITICAL, HIGH, MEDIUM, LOW }

    public enum FindingType {
        PUBLIC_PRINCIPAL_GRANT,
        JCR_ALL_PRIVILEGE,
        WRITE_ON_SENSITIVE_PATH,
        MISSING_DENY_ON_SENSITIVE_PATH
    }

    public record AclFinding(
        String filePath,
        String jcrPath,
        Severity severity,
        FindingType type,
        String message,
        String recommendation
    ) {}

    public record AnalysisResult(List<AclFinding> findings) {

        public long countBySeverity(Severity severity) {
            return findings.stream().filter(f -> f.severity() == severity).count();
        }

        public boolean hasCritical() {
            return findings.stream().anyMatch(f -> f.severity() == Severity.CRITICAL);
        }
    }
}
