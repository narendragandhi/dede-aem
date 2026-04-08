package com.dede.cloud;

import com.dede.domain.GraphService;
import com.dede.domain.model.CodeNode;
import com.dede.domain.model.NodeType;
import com.dede.domain.model.RelationshipType;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Scans Java source code for usage of forbidden APIs and deprecated patterns.
 *
 * Creates VULNERABILITY nodes in the graph linked to the code that uses forbidden APIs.
 */
@Component
public class ForbiddenApiScanner {

    private static final Logger log = LoggerFactory.getLogger(ForbiddenApiScanner.class);

    private final GraphService graphService;
    private final ForbiddenApiCatalog catalog;
    private final JavaParser javaParser;

    public ForbiddenApiScanner(GraphService graphService, ForbiddenApiCatalog catalog) {
        this.graphService = graphService;
        this.catalog = catalog;
        this.javaParser = new JavaParser();
    }

    /**
     * Scans a project directory for forbidden API usage.
     */
    public ScanResult scanProject(Path projectRoot) {
        List<ForbiddenApiViolation> violations = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(projectRoot)) {
            paths.filter(Files::isRegularFile)
                 .filter(p -> p.toString().endsWith(".java"))
                 .forEach(javaFile -> {
                     List<ForbiddenApiViolation> fileViolations = scanJavaFile(javaFile);
                     violations.addAll(fileViolations);
                 });
        } catch (IOException e) {
            log.error("Failed to scan project: {}", projectRoot, e);
        }

        // Create vulnerability nodes for each violation
        for (ForbiddenApiViolation violation : violations) {
            createVulnerabilityNode(violation);
        }

        return new ScanResult(violations);
    }

    /**
     * Scans a single Java file for forbidden API usage.
     */
    public List<ForbiddenApiViolation> scanJavaFile(Path javaFile) {
        List<ForbiddenApiViolation> violations = new ArrayList<>();

        try {
            String source = Files.readString(javaFile);
            ParseResult<CompilationUnit> parseResult = javaParser.parse(source);

            if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
                log.debug("Failed to parse {}", javaFile);
                return violations;
            }

            CompilationUnit cu = parseResult.getResult().get();
            String filePath = javaFile.toString();

            // Collect imports for fully qualified name resolution
            Map<String, String> importMap = buildImportMap(cu);

            // Scan imports
            violations.addAll(scanImports(cu, filePath));

            // Scan annotations
            violations.addAll(scanAnnotations(cu, filePath, importMap));

            // Scan method calls
            violations.addAll(scanMethodCalls(cu, filePath, importMap));

        } catch (IOException e) {
            log.warn("Failed to read Java file: {}", javaFile, e);
        }

        return violations;
    }

    /**
     * Builds a map of simple class names to fully qualified names from imports.
     */
    private Map<String, String> buildImportMap(CompilationUnit cu) {
        Map<String, String> importMap = new HashMap<>();

        for (ImportDeclaration importDecl : cu.getImports()) {
            String importName = importDecl.getNameAsString();
            if (!importDecl.isAsterisk()) {
                String simpleName = importName.substring(importName.lastIndexOf('.') + 1);
                importMap.put(simpleName, importName);
            }
        }

        return importMap;
    }

    /**
     * Scans import declarations for forbidden APIs.
     */
    private List<ForbiddenApiViolation> scanImports(CompilationUnit cu, String filePath) {
        List<ForbiddenApiViolation> violations = new ArrayList<>();

        for (ImportDeclaration importDecl : cu.getImports()) {
            String importName = importDecl.getNameAsString();
            int line = importDecl.getBegin().map(p -> p.line).orElse(0);

            // Check if this import is forbidden
            Optional<ForbiddenApiCatalog.ForbiddenMatch> match = catalog.checkClass(importName);
            if (match.isPresent()) {
                violations.add(new ForbiddenApiViolation(
                    filePath,
                    line,
                    ViolationType.FORBIDDEN_IMPORT,
                    importName,
                    match.get().categoryName(),
                    match.get().severity(),
                    match.get().description(),
                    match.get().replacement()
                ));
            }
        }

        return violations;
    }

    /**
     * Scans annotations for deprecated patterns.
     */
    private List<ForbiddenApiViolation> scanAnnotations(CompilationUnit cu, String filePath,
                                                         Map<String, String> importMap) {
        List<ForbiddenApiViolation> violations = new ArrayList<>();

        cu.findAll(AnnotationExpr.class).forEach(annotation -> {
            String annotationName = annotation.getNameAsString();
            int line = annotation.getBegin().map(p -> p.line).orElse(0);

            // Resolve fully qualified name
            String fqn = importMap.getOrDefault(annotationName, annotationName);

            Optional<ForbiddenApiCatalog.DeprecatedAnnotation> deprecated =
                catalog.checkAnnotation(fqn);

            if (deprecated.isPresent()) {
                violations.add(new ForbiddenApiViolation(
                    filePath,
                    line,
                    ViolationType.DEPRECATED_ANNOTATION,
                    fqn,
                    "DEPRECATED_ANNOTATION",
                    ForbiddenApiCatalog.Severity.MEDIUM,
                    deprecated.get().description(),
                    deprecated.get().replacement()
                ));
            }
        });

        return violations;
    }

    /**
     * Scans method calls for forbidden patterns like loginAdministrative and supply-chain risks.
     *
     * For supply-chain rules (class-scoped methods), the caller's type is resolved from:
     * 1. Explicit scope: {@code runtime.exec(...)} → scope is "runtime" → look up in import map
     * 2. Static calls: {@code Runtime.exec(...)} → scope is "Runtime" directly
     * 3. No scope (unqualified call): treated as any-class method match only
     */
    private List<ForbiddenApiViolation> scanMethodCalls(CompilationUnit cu, String filePath,
                                                         Map<String, String> importMap) {
        List<ForbiddenApiViolation> violations = new ArrayList<>();

        // Build a reverse map: variable name → import type (best-effort, no full symbol solver)
        Map<String, String> varTypeMap = buildVariableTypeMap(cu, importMap);

        cu.findAll(MethodCallExpr.class).forEach(methodCall -> {
            String methodName = methodCall.getNameAsString();
            int line = methodCall.getBegin().map(p -> p.line).orElse(0);

            Optional<ForbiddenApiCatalog.ForbiddenMatch> match = Optional.empty();

            // Try class-scoped check first when there is a scope expression
            if (methodCall.getScope().isPresent()) {
                String scopeStr = methodCall.getScope().get().toString();
                // Resolve: could be a variable name or a class name
                String resolvedType = varTypeMap.getOrDefault(scopeStr,
                    importMap.getOrDefault(scopeStr, scopeStr));
                // Extract simple name for matching
                String simpleName = resolvedType.substring(resolvedType.lastIndexOf('.') + 1);
                match = catalog.checkClassScopedMethod(simpleName, methodName);
                // Also try FQN
                if (match.isEmpty() && !resolvedType.equals(simpleName)) {
                    match = catalog.checkClassScopedMethod(resolvedType, methodName);
                }
            }

            // Fall back to any-class method check (methods with empty classes list)
            if (match.isEmpty()) {
                match = catalog.checkMethod(methodName);
            }

            if (match.isPresent()) {
                violations.add(new ForbiddenApiViolation(
                    filePath,
                    line,
                    ViolationType.FORBIDDEN_METHOD_CALL,
                    methodName,
                    match.get().categoryName(),
                    match.get().severity(),
                    match.get().description(),
                    match.get().replacement()
                ));
            }
        });

        return violations;
    }

    /**
     * Best-effort variable-to-type map built from local variable declarations.
     * Handles patterns like: {@code Runtime rt = Runtime.getRuntime(); rt.exec(...)}
     */
    private Map<String, String> buildVariableTypeMap(CompilationUnit cu,
                                                      Map<String, String> importMap) {
        Map<String, String> varTypeMap = new HashMap<>();
        cu.findAll(com.github.javaparser.ast.body.VariableDeclarator.class).forEach(vd -> {
            String varName = vd.getNameAsString();
            String typeStr = vd.getTypeAsString();
            // Resolve to FQN if possible via import map
            String resolved = importMap.getOrDefault(typeStr, typeStr);
            varTypeMap.put(varName, resolved);
        });
        return varTypeMap;
    }

    /**
     * Creates a VULNERABILITY node in the graph for a violation.
     */
    private void createVulnerabilityNode(ForbiddenApiViolation violation) {
        String nodeId = String.format("vuln:forbidden-api:%s:%d:%s",
            sanitizeForId(violation.filePath()),
            violation.line(),
            sanitizeForId(violation.target()));

        CodeNode vulnNode = new CodeNode(
            nodeId,
            violation.target(),
            NodeType.VULNERABILITY,
            violation.description(),
            violation.filePath()
        );

        vulnNode.getProperties().put("line", String.valueOf(violation.line()));
        vulnNode.getProperties().put("category", violation.category());
        vulnNode.getProperties().put("severity", violation.severity().name());
        vulnNode.getProperties().put("violationType", violation.type().name());
        if (violation.replacement() != null) {
            vulnNode.getProperties().put("replacement", violation.replacement());
        }

        graphService.addNode(vulnNode);

        // Try to link to the class/file that contains this violation
        linkVulnerabilityToSource(vulnNode, violation);
    }

    /**
     * Links a vulnerability node to its source class/file.
     */
    private void linkVulnerabilityToSource(CodeNode vulnNode, ForbiddenApiViolation violation) {
        String filePath = violation.filePath();

        // Find any class node that has this file path
        Optional<CodeNode> sourceNode = graphService.getAllNodes().stream()
            .filter(n -> n.getType() == NodeType.CLASS || n.getType() == NodeType.INTERFACE)
            .filter(n -> filePath.equals(n.getFilePath()))
            .findFirst();

        sourceNode.ifPresent(source ->
            graphService.addEdge(vulnNode, source, RelationshipType.EXPOSES)
        );
    }

    private String sanitizeForId(String input) {
        return input.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }

    // --- Administrative Session Detection (Bead 3.4) ---

    /**
     * Specifically scans for administrative session usage patterns.
     */
    public List<AdminSessionViolation> scanForAdminSessions(Path projectRoot) {
        List<AdminSessionViolation> violations = new ArrayList<>();

        Set<String> adminPatterns = Set.of(
            "loginAdministrative",
            "getAdministrativeResourceResolver",
            "impersonateUsingAdministrator"
        );

        try (Stream<Path> paths = Files.walk(projectRoot)) {
            paths.filter(Files::isRegularFile)
                 .filter(p -> p.toString().endsWith(".java"))
                 .forEach(javaFile -> {
                     try {
                         String source = Files.readString(javaFile);
                         ParseResult<CompilationUnit> parseResult = javaParser.parse(source);

                         if (parseResult.isSuccessful() && parseResult.getResult().isPresent()) {
                             CompilationUnit cu = parseResult.getResult().get();

                             cu.findAll(MethodCallExpr.class).forEach(methodCall -> {
                                 String methodName = methodCall.getNameAsString();

                                 if (adminPatterns.contains(methodName)) {
                                     int line = methodCall.getBegin().map(p -> p.line).orElse(0);

                                     // Try to get the containing class
                                     String className = methodCall.findAncestor(ClassOrInterfaceDeclaration.class)
                                         .map(c -> c.getNameAsString())
                                         .orElse("Unknown");

                                     violations.add(new AdminSessionViolation(
                                         javaFile.toString(),
                                         line,
                                         methodName,
                                         className,
                                         suggestServiceUserFix(className, methodName)
                                     ));
                                 }
                             });
                         }
                     } catch (IOException e) {
                         log.warn("Failed to scan file for admin sessions: {}", javaFile, e);
                     }
                 });
        } catch (IOException e) {
            log.error("Failed to scan project for admin sessions: {}", projectRoot, e);
        }

        return violations;
    }

    /**
     * Suggests a fix for administrative session usage.
     */
    private String suggestServiceUserFix(String className, String methodName) {
        return String.format("""
            Replace %s() with service user authentication:

            1. Create a service user mapping in OSGi config:
               org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended-<project>.cfg.json
               {
                 "user.mapping": ["<bundle-symbolic-name>:<subservice>=%s-service-user"]
               }

            2. Update code to use ServiceUserMapped:
               @Reference(target = "(subServiceName=<subservice>)")
               private ResourceResolverFactory resolverFactory;

               Map<String, Object> params = Collections.singletonMap(
                   ResourceResolverFactory.SUBSERVICE, "<subservice>");
               ResourceResolver resolver = resolverFactory.getServiceResourceResolver(params);
            """, methodName, className.toLowerCase());
    }

    // --- Record Types ---

    public enum ViolationType {
        FORBIDDEN_IMPORT,
        FORBIDDEN_METHOD_CALL,
        DEPRECATED_ANNOTATION,
        ADMIN_SESSION
    }

    public record ForbiddenApiViolation(
        String filePath,
        int line,
        ViolationType type,
        String target,
        String category,
        ForbiddenApiCatalog.Severity severity,
        String description,
        String replacement
    ) {}

    public record AdminSessionViolation(
        String filePath,
        int line,
        String methodName,
        String className,
        String suggestedFix
    ) {}

    public record ScanResult(
        List<ForbiddenApiViolation> violations
    ) {
        public int getTotalViolations() {
            return violations.size();
        }

        public int getCriticalCount() {
            return (int) violations.stream()
                .filter(v -> v.severity() == ForbiddenApiCatalog.Severity.CRITICAL)
                .count();
        }

        public int getHighCount() {
            return (int) violations.stream()
                .filter(v -> v.severity() == ForbiddenApiCatalog.Severity.HIGH)
                .count();
        }

        public Map<String, Long> getViolationsByCategory() {
            return violations.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                    ForbiddenApiViolation::category,
                    java.util.stream.Collectors.counting()
                ));
        }

        public List<ForbiddenApiViolation> getViolationsBySeverity(ForbiddenApiCatalog.Severity severity) {
            return violations.stream()
                .filter(v -> v.severity() == severity)
                .toList();
        }
    }
}
