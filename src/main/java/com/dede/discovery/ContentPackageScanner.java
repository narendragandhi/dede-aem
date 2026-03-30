package com.dede.discovery;

import com.dede.domain.GraphService;
import com.dede.domain.model.CodeNode;
import com.dede.domain.model.NodeType;
import com.dede.domain.model.RelationshipType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deep Content Package Scanner for AEM projects.
 *
 * Scans unpacked content packages (jcr_root) directly from source to detect:
 * - ACL issues from rep:policy nodes
 * - Hardcoded paths in content
 * - Cloud-incompatible features (vanity URLs, custom workflows)
 * - filter.xml scope analysis
 * - Node type definition (.cnd) validation
 * - Content structure analysis
 */
@Component
public class ContentPackageScanner {

    private static final Logger log = LoggerFactory.getLogger(ContentPackageScanner.class);

    private final GraphService graphService;
    private final DocumentBuilderFactory factory;

    // Patterns for detecting issues
    private static final Pattern HARDCODED_DAM_PATH = Pattern.compile("/content/dam/[a-zA-Z0-9/_-]+");
    private static final Pattern HARDCODED_CONTENT_PATH = Pattern.compile("/content/[a-zA-Z0-9/_-]+");
    private static final Pattern VANITY_URL_PATTERN = Pattern.compile("sling:vanityPath\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern WORKFLOW_LAUNCHER_PATTERN = Pattern.compile("jcr:primaryType\\s*=\\s*\"cq:WorkflowLauncher\"");

    // Cloud-incompatible paths
    private static final Set<String> CLOUD_INCOMPATIBLE_PATHS = Set.of(
        "/etc/packages",
        "/etc/replication",
        "/etc/map",
        "/var/classes",
        "/var/clientlibs",
        "/libs"
    );

    // Dangerous ACL patterns
    private static final Set<String> DANGEROUS_ACL_PRINCIPALS = Set.of(
        "everyone",
        "anonymous"
    );

    private final List<ContentIssue> issues = new ArrayList<>();

    public ContentPackageScanner(GraphService graphService) {
        this.graphService = graphService;
        this.factory = DocumentBuilderFactory.newInstance();

        // Security hardening
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
        } catch (Exception e) {
            log.warn("Failed to configure secure XML parser", e);
        }
    }

    /**
     * Scan a content package directory (jcr_root).
     */
    public List<ContentIssue> scanContentPackage(Path jcrRootPath) throws IOException {
        issues.clear();

        if (!Files.exists(jcrRootPath)) {
            log.warn("jcr_root path does not exist: {}", jcrRootPath);
            return issues;
        }

        log.info("Scanning content package at: {}", jcrRootPath);

        // Scan filter.xml if present
        Path filterXml = jcrRootPath.getParent().resolve("META-INF/vault/filter.xml");
        if (Files.exists(filterXml)) {
            parseFilterXml(filterXml);
        }

        // Walk through jcr_root
        Files.walkFileTree(jcrRootPath, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String fileName = file.getFileName().toString();

                if (fileName.equals(".content.xml")) {
                    analyzeContentXml(file);
                } else if (fileName.endsWith(".cnd")) {
                    analyzeNodeTypeDefinition(file);
                } else if (fileName.equals("_rep_policy.xml")) {
                    analyzeAclPolicy(file);
                }

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                // Check for cloud-incompatible paths
                String pathStr = dir.toString().replace("\\", "/");
                for (String incompatible : CLOUD_INCOMPATIBLE_PATHS) {
                    if (pathStr.contains("/jcr_root" + incompatible)) {
                        issues.add(new ContentIssue(
                            "CONTENT-001",
                            "Cloud-incompatible path",
                            "CRITICAL",
                            "Content package includes cloud-managed path: " + incompatible,
                            dir.toString()
                        ));
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });

        // Create violation nodes for issues
        createViolationNodes();

        log.info("Content package scan complete: found {} issues", issues.size());
        return issues;
    }

    /**
     * Parse filter.xml to understand package scope.
     */
    private void parseFilterXml(Path filterXml) {
        try {
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(filterXml.toFile());

            NodeList filters = doc.getElementsByTagName("filter");
            for (int i = 0; i < filters.getLength(); i++) {
                Element filter = (Element) filters.item(i);
                String root = filter.getAttribute("root");

                // Create a node for this filter root
                CodeNode filterNode = new CodeNode(
                    "filter:" + root,
                    root,
                    NodeType.CONTENT_PACKAGE,
                    "Package filter root: " + root,
                    filterXml.toString()
                );
                graphService.addNode(filterNode);

                // Check for problematic filter roots
                for (String incompatible : CLOUD_INCOMPATIBLE_PATHS) {
                    if (root.startsWith(incompatible)) {
                        issues.add(new ContentIssue(
                            "CONTENT-002",
                            "Filter includes cloud-managed path",
                            "CRITICAL",
                            "Filter root " + root + " includes cloud-managed content",
                            filterXml.toString()
                        ));
                    }
                }

                // Check for includes/excludes
                NodeList includes = filter.getElementsByTagName("include");
                NodeList excludes = filter.getElementsByTagName("exclude");
                filterNode.getProperties().put("includeCount", String.valueOf(includes.getLength()));
                filterNode.getProperties().put("excludeCount", String.valueOf(excludes.getLength()));
            }

            log.debug("Parsed filter.xml: {} filter roots", filters.getLength());

        } catch (Exception e) {
            log.warn("Failed to parse filter.xml: {}", filterXml, e);
        }
    }

    /**
     * Analyze .content.xml files for issues.
     */
    private void analyzeContentXml(Path contentXml) {
        try {
            String content = Files.readString(contentXml);
            String pathStr = contentXml.toString();

            // Check for vanity URLs (not supported in Cloud Service)
            Matcher vanityMatcher = VANITY_URL_PATTERN.matcher(content);
            while (vanityMatcher.find()) {
                String vanityPath = vanityMatcher.group(1);
                issues.add(new ContentIssue(
                    "CONTENT-003",
                    "Vanity URL usage",
                    "MAJOR",
                    "Vanity URL '" + vanityPath + "' should use CDN/Dispatcher redirects instead",
                    pathStr
                ));
            }

            // Check for workflow launchers
            if (WORKFLOW_LAUNCHER_PATTERN.matcher(content).find()) {
                issues.add(new ContentIssue(
                    "CONTENT-004",
                    "Custom workflow launcher",
                    "MAJOR",
                    "Custom workflow launcher detected - review for Cloud Service compatibility",
                    pathStr
                ));
            }

            // Check for hardcoded DAM paths
            Matcher damMatcher = HARDCODED_DAM_PATH.matcher(content);
            Set<String> foundDamPaths = new HashSet<>();
            while (damMatcher.find()) {
                String damPath = damMatcher.group();
                if (!foundDamPaths.contains(damPath) && damPath.length() > 15) {
                    foundDamPaths.add(damPath);
                }
            }
            if (foundDamPaths.size() > 3) {
                issues.add(new ContentIssue(
                    "CONTENT-005",
                    "Multiple hardcoded DAM paths",
                    "MINOR",
                    "Found " + foundDamPaths.size() + " hardcoded DAM paths - consider using references",
                    pathStr
                ));
            }

            // Check for rep:policy (ACL) in content
            if (content.contains("rep:policy") || content.contains("rep:ACL")) {
                analyzeInlineAcl(content, pathStr);
            }

        } catch (IOException e) {
            log.debug("Failed to read content.xml: {}", contentXml);
        }
    }

    /**
     * Analyze ACL policy files.
     */
    private void analyzeAclPolicy(Path policyFile) {
        try {
            String content = Files.readString(policyFile);
            String pathStr = policyFile.toString();

            // Check for dangerous principals
            for (String principal : DANGEROUS_ACL_PRINCIPALS) {
                if (content.contains("rep:principalName=\"" + principal + "\"")) {
                    issues.add(new ContentIssue(
                        "CONTENT-006",
                        "ACL grants to " + principal,
                        "CRITICAL",
                        "ACL policy grants permissions to '" + principal + "' - review security implications",
                        pathStr
                    ));
                }
            }

            // Check for jcr:all permissions
            if (content.contains("jcr:all") || content.contains("rep:write")) {
                issues.add(new ContentIssue(
                    "CONTENT-007",
                    "Overly permissive ACL",
                    "MAJOR",
                    "ACL grants broad permissions (jcr:all or rep:write) - follow least-privilege principle",
                    pathStr
                ));
            }

            // Create node for ACL
            CodeNode aclNode = new CodeNode(
                "acl:" + pathStr,
                "ACL Policy",
                NodeType.JCR_COMPONENT,
                "Access Control Policy",
                pathStr
            );
            aclNode.getProperties().put("type", "rep:policy");
            graphService.addNode(aclNode);

        } catch (IOException e) {
            log.debug("Failed to read ACL policy: {}", policyFile);
        }
    }

    /**
     * Analyze inline ACL definitions in content.xml.
     */
    private void analyzeInlineAcl(String content, String filePath) {
        // Check for allow all patterns
        if (content.contains("allow") && (content.contains("jcr:all") || content.contains("rep:write"))) {
            issues.add(new ContentIssue(
                "CONTENT-008",
                "Inline ACL with broad permissions",
                "MAJOR",
                "Content contains inline ACL with broad permissions",
                filePath
            ));
        }
    }

    /**
     * Analyze node type definition files (.cnd).
     */
    private void analyzeNodeTypeDefinition(Path cndFile) {
        try {
            String content = Files.readString(cndFile);
            String pathStr = cndFile.toString();

            // Create node for CND
            CodeNode cndNode = new CodeNode(
                "cnd:" + pathStr,
                cndFile.getFileName().toString(),
                NodeType.JCR_COMPONENT,
                "Node Type Definition",
                pathStr
            );
            graphService.addNode(cndNode);

            // Count node type definitions
            int nodeTypeCount = 0;
            for (String line : content.split("\n")) {
                if (line.trim().startsWith("[") && line.contains("]")) {
                    nodeTypeCount++;
                }
            }
            cndNode.getProperties().put("nodeTypeCount", String.valueOf(nodeTypeCount));

            // Check for potential issues
            if (content.contains("mixin") && content.contains("nt:unstructured")) {
                issues.add(new ContentIssue(
                    "CONTENT-009",
                    "Custom mixin with nt:unstructured",
                    "MINOR",
                    "Custom mixin type using nt:unstructured - consider more specific types",
                    pathStr
                ));
            }

            log.debug("Analyzed CND file: {} ({} node types)", cndFile.getFileName(), nodeTypeCount);

        } catch (IOException e) {
            log.debug("Failed to read CND file: {}", cndFile);
        }
    }

    /**
     * Create violation nodes in the graph for detected issues.
     */
    private void createViolationNodes() {
        for (ContentIssue issue : issues) {
            CodeNode violationNode = new CodeNode(
                "violation:content:" + issue.ruleId + ":" + issue.filePath.hashCode(),
                issue.ruleId + ": " + issue.title,
                NodeType.VULNERABILITY,
                issue.description,
                issue.filePath
            );
            violationNode.getProperties().put("ruleId", issue.ruleId);
            violationNode.getProperties().put("severity", issue.severity);
            violationNode.getProperties().put("category", "CONTENT_PACKAGE");
            graphService.addNode(violationNode);
        }
    }

    /**
     * Get the list of detected issues.
     */
    public List<ContentIssue> getIssues() {
        return Collections.unmodifiableList(issues);
    }

    /**
     * Content issue record.
     */
    public static class ContentIssue {
        public final String ruleId;
        public final String title;
        public final String severity;
        public final String description;
        public final String filePath;

        public ContentIssue(String ruleId, String title, String severity,
                           String description, String filePath) {
            this.ruleId = ruleId;
            this.title = title;
            this.severity = severity;
            this.description = description;
            this.filePath = filePath;
        }
    }
}
