package com.dede.security;

import com.dede.domain.GraphService;
import com.dede.domain.model.CodeNode;
import com.dede.domain.model.NodeType;
import com.dede.domain.model.RelationshipType;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Audits Sling servlet registrations for security issues:
 *
 * <ul>
 *   <li>PATH-based servlet registration (higher attack surface than resource-type)</li>
 *   <li>Dangerous HTTP methods (DELETE, PUT) exposed on path-based servlets</li>
 *   <li>Missing authentication enforcement — no checkPermission / requiresAuthentication</li>
 *   <li>GET servlets that write to the repository (state-changing GETs)</li>
 * </ul>
 */
@Component
public class ServletSecurityAuditor {

    private static final Logger log = LoggerFactory.getLogger(ServletSecurityAuditor.class);

    private static final Set<String> DANGEROUS_METHODS = Set.of("DELETE", "PUT", "PATCH");
    private static final Set<String> AUTH_CHECK_METHODS = Set.of(
        "checkPermission", "checkAccess", "hasPermission",
        "requiresAuthentication", "isAnonymous", "getUserID"
    );

    private final GraphService graphService;

    public ServletSecurityAuditor(GraphService graphService) {
        this.graphService = graphService;
    }

    /**
     * Audits all SLING_SERVLET nodes in the graph plus their source files.
     */
    public AuditResult audit(Path projectRoot) {
        List<ServletFinding> findings = new ArrayList<>();

        List<CodeNode> servlets = graphService.getAllNodes().stream()
            .filter(n -> n.getType() == NodeType.SLING_SERVLET)
            .toList();

        log.info("Auditing {} SLING_SERVLET nodes", servlets.size());

        for (CodeNode servlet : servlets) {
            findings.addAll(auditServletNode(servlet, projectRoot));
        }

        // Also do AST scan for servlet source files not yet in graph
        findings.addAll(scanServletSources(projectRoot));

        return new AuditResult(findings);
    }

    private List<ServletFinding> auditServletNode(CodeNode servlet, Path projectRoot) {
        List<ServletFinding> findings = new ArrayList<>();
        String nodeId = servlet.getId();
        String filePath = servlet.getFilePath();

        // 1. Check for path-based registration (riskier than resource-type)
        boolean isPathBased = graphService.getOutgoingNodes(servlet).stream()
            .anyMatch(n -> n.getType() == NodeType.SLING_PATH);

        if (isPathBased) {
            findings.add(new ServletFinding(
                nodeId, filePath, Severity.MEDIUM,
                FindingType.PATH_BASED_REGISTRATION,
                "Servlet registered via path. Path-based servlets bypass Sling's resource " +
                "resolution and are harder to control via Dispatcher filters. " +
                "Prefer @SlingServletResourceTypes.",
                "Replace @SlingServletPaths with @SlingServletResourceTypes and restrict via Dispatcher."
            ));
        }

        // 2. Check for dangerous HTTP methods on path-based servlets
        Set<String> exposedMethods = graphService.getOutgoingNodes(servlet).stream()
            .filter(n -> n.getType() == NodeType.METHOD)
            .map(CodeNode::getName)
            .map(String::toUpperCase)
            .collect(java.util.stream.Collectors.toSet());

        for (String method : DANGEROUS_METHODS) {
            if (exposedMethods.contains(method)) {
                Severity sev = isPathBased ? Severity.HIGH : Severity.MEDIUM;
                findings.add(new ServletFinding(
                    nodeId, filePath, sev,
                    FindingType.DANGEROUS_HTTP_METHOD,
                    String.format("Servlet exposes %s method%s.", method,
                        isPathBased ? " on a path-based registration" : ""),
                    "Ensure " + method + " requires authentication and validate CSRF token."
                ));
            }
        }

        // 3. AST-based auth check if source file is known
        if (filePath != null) {
            findings.addAll(auditServletSource(Path.of(filePath), nodeId, isPathBased));
        }

        return findings;
    }

    private List<ServletFinding> auditServletSource(Path sourceFile, String nodeId,
                                                      boolean isPathBased) {
        List<ServletFinding> findings = new ArrayList<>();
        if (!Files.exists(sourceFile)) return findings;

        try {
            String source = Files.readString(sourceFile);
            var parseResult = new JavaParser().parse(source);
            if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) return findings;

            CompilationUnit cu = parseResult.getResult().get();

            // Check each doGet/doPost/doDelete/doPut method
            cu.findAll(MethodDeclaration.class).forEach(method -> {
                String name = method.getNameAsString();
                if (!name.startsWith("do")) return;

                String httpVerb = name.substring(2).toUpperCase();
                boolean isDangerous = DANGEROUS_METHODS.contains(httpVerb);

                // Check if method body contains auth validation
                String body = method.getBody().map(Object::toString).orElse("");
                boolean hasAuthCheck = AUTH_CHECK_METHODS.stream().anyMatch(body::contains);

                if (isDangerous && !hasAuthCheck) {
                    findings.add(new ServletFinding(
                        nodeId, sourceFile.toString(), Severity.HIGH,
                        FindingType.MISSING_AUTH_CHECK,
                        String.format("do%s() has no detectable authentication check " +
                            "(checkPermission, hasPermission, getUserID, etc.).",
                            httpVerb.charAt(0) + httpVerb.substring(1).toLowerCase()),
                        "Add permission check before processing: " +
                        "session.checkPermission(path, Session.ACTION_SET_PROPERTY)"
                    ));
                }

                // Detect state-changing GET (writes inside doGet)
                if ("GET".equals(httpVerb)) {
                    boolean hasWrite = body.contains(".setProperty(") || body.contains(".addNode(")
                        || body.contains(".commit(") || body.contains(".save(")
                        || body.contains("JobManager") || body.contains("WorkflowSession");
                    if (hasWrite) {
                        findings.add(new ServletFinding(
                            nodeId, sourceFile.toString(), Severity.MEDIUM,
                            FindingType.STATE_CHANGING_GET,
                            "doGet() appears to modify repository state. GET requests must be idempotent.",
                            "Move write operations to doPost() and add CSRF validation."
                        ));
                    }
                }
            });

        } catch (IOException e) {
            log.warn("Could not read servlet source: {}", sourceFile, e);
        }

        return findings;
    }

    /**
     * Fallback scan: find servlet source files not yet in graph via annotation detection.
     */
    private List<ServletFinding> scanServletSources(Path projectRoot) {
        List<ServletFinding> findings = new ArrayList<>();
        if (!Files.exists(projectRoot)) return findings;

        try (Stream<Path> paths = Files.walk(projectRoot)) {
            paths.filter(Files::isRegularFile)
                 .filter(p -> p.toString().endsWith(".java"))
                 .forEach(javaFile -> {
                     try {
                         String source = Files.readString(javaFile);
                         // Quick pre-filter — only parse files that look like servlets
                         if (!source.contains("SlingServletPaths") &&
                             !source.contains("SlingServletResourceTypes")) return;

                         // Skip if already in graph (has a SLING_SERVLET node with this path)
                         boolean inGraph = graphService.getAllNodes().stream()
                             .anyMatch(n -> n.getType() == NodeType.SLING_SERVLET
                                 && javaFile.toString().equals(n.getFilePath()));
                         if (inGraph) return;

                         boolean isPathBased = source.contains("SlingServletPaths");
                         if (isPathBased) {
                             findings.add(new ServletFinding(
                                 javaFile.toString(), javaFile.toString(), Severity.MEDIUM,
                                 FindingType.PATH_BASED_REGISTRATION,
                                 "Servlet registered via path. Path-based servlets bypass Sling's " +
                                 "resource resolution and are harder to control via Dispatcher filters. " +
                                 "Prefer @SlingServletResourceTypes.",
                                 "Replace @SlingServletPaths with @SlingServletResourceTypes."
                             ));
                         }
                         findings.addAll(auditServletSource(javaFile, javaFile.toString(), isPathBased));

                     } catch (IOException e) {
                         log.debug("Skipping {}", javaFile, e);
                     }
                 });
        } catch (IOException e) {
            log.warn("Error walking project for servlet audit: {}", projectRoot, e);
        }

        return findings;
    }

    // --- Types ---

    public enum Severity { CRITICAL, HIGH, MEDIUM, LOW, INFO }

    public enum FindingType {
        PATH_BASED_REGISTRATION,
        DANGEROUS_HTTP_METHOD,
        MISSING_AUTH_CHECK,
        STATE_CHANGING_GET
    }

    public record ServletFinding(
        String servletId,
        String filePath,
        Severity severity,
        FindingType type,
        String message,
        String recommendation
    ) {}

    public record AuditResult(List<ServletFinding> findings) {

        public long countBySeverity(Severity severity) {
            return findings.stream().filter(f -> f.severity() == severity).count();
        }

        public Map<FindingType, List<ServletFinding>> byType() {
            return findings.stream().collect(
                java.util.stream.Collectors.groupingBy(ServletFinding::type));
        }
    }
}
