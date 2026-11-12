package com.dede.cloud;

import com.dede.cloud.ForbiddenApiScanner.ForbiddenApiViolation;
import com.dede.cloud.ForbiddenApiScanner.ViolationType;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.TryStmt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Detectors for rules marked {@code "detection": "usage"} in forbidden-apis.json.
 *
 * <p>These rules are about how a type is <em>used</em>, not whether it is imported: importing
 * {@code ResourceResolver} is normal in almost every AEM class, so matching on the import made
 * the rules fire on nearly every project. Each detector looks for the actual anti-pattern and
 * reports under the catalog's category, so severity and wording stay in the JSON. A category
 * removed from the catalog simply stops being reported.
 */
final class UsagePatternDetector {

    static final String THREAD_UNSAFE_FIELDS = "THREAD_UNSAFE_FIELDS";
    static final String DEPRECATED_DAM = "DEPRECATED_DAM";
    static final String UNCLOSED_RESOURCE_RESOLVER = "UNCLOSED_RESOURCE_RESOLVER";
    static final String HTTP_CLIENT_TIMEOUT = "HTTP_CLIENT_TIMEOUT";
    static final String HTTP_CLIENT_REUSE = "HTTP_CLIENT_REUSE";

    /** Base classes whose subclasses are registered as a single shared servlet instance. */
    private static final Set<String> SERVLET_BASES = Set.of(
        "SlingSafeMethodsServlet", "SlingAllMethodsServlet", "HttpServlet", "GenericServlet");

    /** Calls that hand out client objects with no timeouts at all. */
    private static final Set<String> UNCONFIGURABLE_CLIENT_FACTORIES = Set.of(
        "createDefault", "createSystem", "createMinimal", "newHttpClient");

    /** Calls that return a builder, where timeouts may or may not be set. */
    private static final Set<String> CLIENT_BUILDER_FACTORIES = Set.of("custom", "create", "newBuilder");

    private static final Set<String> CLIENT_TIMEOUT_SETTERS = Set.of(
        "setDefaultRequestConfig", "setConnectTimeout", "setSocketTimeout",
        "setConnectionRequestTimeout", "setResponseTimeout", "connectTimeout");

    private final ForbiddenApiCatalog catalog;

    UsagePatternDetector(ForbiddenApiCatalog catalog) {
        this.catalog = catalog;
    }

    List<ForbiddenApiViolation> detect(CompilationUnit cu, String filePath,
                                       Map<String, String> importMap, Map<String, String> varTypeMap) {
        List<ForbiddenApiViolation> violations = new ArrayList<>();
        detectThreadUnsafeFields(cu, filePath, importMap, violations);
        detectCustomAssetHandlers(cu, filePath, importMap, violations);
        detectUnclosedResolvers(cu, filePath, varTypeMap, violations);
        detectHttpClientIssues(cu, filePath, importMap, varTypeMap, violations);
        return violations;
    }

    // --- THREAD_UNSAFE_FIELDS -------------------------------------------------------------

    /**
     * Flags fields of request-scoped types (ResourceResolver, Session, PageManager, ...) on
     * singletons: OSGi components and servlets, which every request thread shares. Sling Models
     * are created per adaptation, so the same field there is fine and is not reported.
     */
    private void detectThreadUnsafeFields(CompilationUnit cu, String filePath,
                                          Map<String, String> importMap,
                                          List<ForbiddenApiViolation> out) {
        Optional<ForbiddenApiCatalog.ForbiddenCategory> category = catalog.getCategory(THREAD_UNSAFE_FIELDS);
        if (category.isEmpty()) return;
        Set<String> unsafeTypes = Set.copyOf(category.get().classes());

        for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            if (cls.isInterface() || !isSingleton(cls)) continue;
            for (FieldDeclaration field : cls.getFields()) {
                for (VariableDeclarator variable : field.getVariables()) {
                    String fqn = resolveType(variable.getType().getElementType().asString(), importMap);
                    if (unsafeTypes.contains(fqn)) {
                        report(out, category.get(), filePath, variable, fqn + " " + variable.getNameAsString());
                    }
                }
            }
        }
    }

    private static boolean isSingleton(ClassOrInterfaceDeclaration cls) {
        if (cls.getAnnotationByName("Model").isPresent()) return false;
        if (cls.getAnnotationByName("Component").isPresent()) return true;
        return cls.getExtendedTypes().stream().anyMatch(t -> SERVLET_BASES.contains(t.getNameAsString()));
    }

    // --- DEPRECATED_DAM (custom asset handlers) -------------------------------------------

    /**
     * Flags classes that implement or extend an asset handler. Merely using a handler to read
     * an image (as the Core Components' AdaptiveImageServlet does) is supported and not reported.
     */
    private void detectCustomAssetHandlers(CompilationUnit cu, String filePath,
                                           Map<String, String> importMap,
                                           List<ForbiddenApiViolation> out) {
        Optional<ForbiddenApiCatalog.ForbiddenCategory> category = catalog.getCategory(DEPRECATED_DAM);
        if (category.isEmpty()) return;
        Set<String> handlerTypes = Set.copyOf(category.get().classes());

        for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            cls.getExtendedTypes().stream()
                .map(t -> resolveType(t.getNameWithScope(), importMap))
                .filter(handlerTypes::contains)
                .forEach(fqn -> report(out, category.get(), filePath, cls, fqn));
            cls.getImplementedTypes().stream()
                .map(t -> resolveType(t.getNameWithScope(), importMap))
                .filter(handlerTypes::contains)
                .forEach(fqn -> report(out, category.get(), filePath, cls, fqn));
        }
    }

    // --- UNCLOSED_RESOURCE_RESOLVER -------------------------------------------------------

    /**
     * Flags resolvers opened from a ResourceResolverFactory that are neither managed by
     * try-with-resources, returned to the caller, nor closed via {@code close()}.
     * Resolvers from {@code request.getResourceResolver()} / {@code resource.getResourceResolver()}
     * belong to Sling and must not be closed, so only factory calls (which take an auth map) count.
     */
    private void detectUnclosedResolvers(CompilationUnit cu, String filePath,
                                         Map<String, String> varTypeMap,
                                         List<ForbiddenApiViolation> out) {
        Optional<ForbiddenApiCatalog.ForbiddenCategory> category = catalog.getCategory(UNCLOSED_RESOURCE_RESOLVER);
        if (category.isEmpty()) return;

        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            if (!opensResolver(call, varTypeMap)) continue;
            if (isTryWithResource(call) || isReturned(call)) continue;

            Optional<Assignee> assignee = assignee(call);
            if (assignee.isPresent() && isClosed(call, assignee.get())) continue;

            report(out, category.get(), filePath, call, call.getNameAsString());
        }
    }

    private static boolean opensResolver(MethodCallExpr call, Map<String, String> varTypeMap) {
        String name = call.getNameAsString();
        if ("getServiceResourceResolver".equals(name) || "getAdministrativeResourceResolver".equals(name)) {
            return true;
        }
        if (!"getResourceResolver".equals(name) || call.getArguments().isEmpty()) {
            return false;
        }
        return call.getScope()
            .map(scope -> simpleName(varTypeMap.getOrDefault(scope.toString(), scope.toString())))
            .map("ResourceResolverFactory"::equals)
            .orElse(false);
    }

    private static boolean isTryWithResource(Node node) {
        Optional<TryStmt> tryStmt = node.findAncestor(TryStmt.class);
        while (tryStmt.isPresent()) {
            for (Expression resource : tryStmt.get().getResources()) {
                if (resource == node || resource.isAncestorOf(node)) return true;
            }
            tryStmt = tryStmt.get().findAncestor(TryStmt.class);
        }
        return false;
    }

    private static boolean isReturned(MethodCallExpr call) {
        return call.getParentNode().filter(p -> p instanceof ReturnStmt).isPresent();
    }

    /** Where the opened resolver is stored: a local variable or a field. */
    private record Assignee(String name, boolean local) {}

    private static Optional<Assignee> assignee(MethodCallExpr call) {
        Optional<Node> parent = call.getParentNode();
        if (parent.isEmpty()) return Optional.empty();
        if (parent.get() instanceof VariableDeclarator vd) {
            boolean local = vd.findAncestor(FieldDeclaration.class).isEmpty();
            return Optional.of(new Assignee(vd.getNameAsString(), local));
        }
        if (parent.get() instanceof AssignExpr assign) {
            Expression target = assign.getTarget();
            if (target instanceof NameExpr n) return Optional.of(new Assignee(n.getNameAsString(), false));
            if (target instanceof FieldAccessExpr f) return Optional.of(new Assignee(f.getNameAsString(), false));
        }
        return Optional.empty();
    }

    /**
     * A local resolver must be closed (or returned, or used as a try resource) in the same
     * method; a field may be closed anywhere in the class, typically in {@code @Deactivate}.
     */
    private static boolean isClosed(MethodCallExpr call, Assignee assignee) {
        Optional<? extends Node> searchRoot = assignee.local()
            ? call.findAncestor(CallableDeclaration.class)
            : call.findAncestor(TypeDeclaration.class);
        if (searchRoot.isEmpty()) return false;
        Node root = searchRoot.get();
        String name = assignee.name();

        boolean closed = root.findAll(MethodCallExpr.class).stream().anyMatch(m ->
            "close".equals(m.getNameAsString())
                && m.getScope().map(s -> s.toString().equals(name) || s.toString().equals("this." + name)).orElse(false));
        if (closed) return true;

        boolean returned = root.findAll(ReturnStmt.class).stream()
            .anyMatch(r -> r.getExpression().map(e -> e.toString().equals(name)).orElse(false));
        if (returned) return true;

        return root.findAll(TryStmt.class).stream()
            .flatMap(t -> t.getResources().stream())
            .anyMatch(r -> r.toString().equals(name) || r.toString().endsWith("= " + name));
    }

    // --- HTTP_CLIENT_TIMEOUT / HTTP_CLIENT_REUSE ------------------------------------------

    private void detectHttpClientIssues(CompilationUnit cu, String filePath,
                                        Map<String, String> importMap, Map<String, String> varTypeMap,
                                        List<ForbiddenApiViolation> out) {
        Optional<ForbiddenApiCatalog.ForbiddenCategory> timeout = catalog.getCategory(HTTP_CLIENT_TIMEOUT);
        Optional<ForbiddenApiCatalog.ForbiddenCategory> reuse = catalog.getCategory(HTTP_CLIENT_REUSE);

        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();

            if ("openConnection".equals(name) && isUrlScope(call, importMap, varTypeMap)) {
                // A factory method that returns the connection leaves timeouts to its caller.
                boolean returned = call.findAncestor(ReturnStmt.class).isPresent();
                if (timeout.isPresent() && !returned && !callableCalls(call, "setConnectTimeout", "setReadTimeout")) {
                    report(out, timeout.get(), filePath, call, "URL.openConnection");
                }
                continue;
            }

            String owner = clientFactoryOwner(call, importMap);
            if (owner == null) continue;

            if (timeout.isPresent()) {
                boolean noTimeouts = UNCONFIGURABLE_CLIENT_FACTORIES.contains(name)
                    || (CLIENT_BUILDER_FACTORIES.contains(name) && !typeCallsAny(call, CLIENT_TIMEOUT_SETTERS));
                if (noTimeouts) {
                    report(out, timeout.get(), filePath, call, owner + "." + name);
                }
            }
            if (reuse.isPresent() && isPerCallCreation(call)) {
                report(out, reuse.get(), filePath, call, owner + "." + name);
            }
        }
    }

    /** Returns "HttpClients"/"HttpClientBuilder"/"HttpClient" for client factory calls, else null. */
    private static String clientFactoryOwner(MethodCallExpr call, Map<String, String> importMap) {
        if (call.getScope().isEmpty()) return null;
        String scope = call.getScope().get().toString();
        String owner = simpleName(importMap.getOrDefault(scope, scope));
        String name = call.getNameAsString();
        return switch (owner) {
            case "HttpClients" -> (UNCONFIGURABLE_CLIENT_FACTORIES.contains(name) || "custom".equals(name)) ? owner : null;
            case "HttpClientBuilder" -> "create".equals(name) ? owner : null;
            case "HttpClient" -> {
                // java.net.http.HttpClient only; Apache's HttpClient interface has no static factories.
                String fqn = importMap.getOrDefault("HttpClient", "java.net.http.HttpClient");
                boolean jdk = fqn.startsWith("java.net.http.") || scope.startsWith("java.net.http.");
                yield jdk && ("newHttpClient".equals(name) || "newBuilder".equals(name)) ? owner : null;
            }
            default -> null;
        };
    }

    private static boolean isUrlScope(MethodCallExpr call, Map<String, String> importMap,
                                      Map<String, String> varTypeMap) {
        if (call.getScope().isEmpty()) return false;
        Expression scope = call.getScope().get();
        if (scope instanceof ObjectCreationExpr oce) {
            return "URL".equals(oce.getType().getNameAsString());
        }
        String type = varTypeMap.getOrDefault(scope.toString(), importMap.getOrDefault(scope.toString(), ""));
        return "URL".equals(simpleName(type));
    }

    /**
     * Creating a client inside an ordinary method means a new connection pool per call.
     * Constructors, field initializers, {@code @Activate}/{@code @Modified} methods and
     * factory methods that return the client are where a reusable client is built.
     */
    private static boolean isPerCallCreation(MethodCallExpr call) {
        Optional<MethodDeclaration> method = call.findAncestor(MethodDeclaration.class);
        if (method.isEmpty()) return false;
        MethodDeclaration m = method.get();
        if (m.getAnnotationByName("Activate").isPresent() || m.getAnnotationByName("Modified").isPresent()) {
            return false;
        }
        String returnType = m.getType().asString();
        return !(returnType.contains("HttpClient") || returnType.endsWith("Builder"));
    }

    private static boolean callableCalls(Node node, String... methodNames) {
        Optional<? extends Node> root = node.findAncestor(CallableDeclaration.class);
        if (root.isEmpty()) return false;
        Set<String> called = Set.copyOf(root.get().findAll(MethodCallExpr.class).stream()
            .map(MethodCallExpr::getNameAsString).toList());
        for (String required : methodNames) {
            if (!called.contains(required)) return false;
        }
        return true;
    }

    private static boolean typeCallsAny(Node node, Set<String> methodNames) {
        Optional<? extends Node> root = node.findAncestor(TypeDeclaration.class);
        return root.isPresent() && root.get().findAll(MethodCallExpr.class).stream()
            .anyMatch(m -> methodNames.contains(m.getNameAsString()));
    }

    // --- helpers --------------------------------------------------------------------------

    private static String resolveType(String type, Map<String, String> importMap) {
        int generic = type.indexOf('<');
        String raw = generic >= 0 ? type.substring(0, generic) : type;
        return raw.contains(".") ? raw : importMap.getOrDefault(raw, raw);
    }

    private static String simpleName(String type) {
        return type.substring(type.lastIndexOf('.') + 1);
    }

    private static int line(Node node) {
        return node.getBegin().map(p -> p.line).orElse(0);
    }

    /**
     * Honors {@code @SuppressWarnings} naming the rule on the flagged node or any enclosing
     * declaration, using the same ids Cloud Manager's SonarQube rules accept: the full rule id
     * ("AEM Rules:AEM-3", "CQRules:CQBP-72"), its short form ("AEM-3"), or the category name.
     */
    private static boolean isSuppressed(Node node, ForbiddenApiCatalog.ForbiddenCategory category) {
        Set<String> accepted = new java.util.HashSet<>();
        accepted.add(category.name());
        if (category.ruleId() != null) {
            String shortId = category.ruleId().substring(category.ruleId().lastIndexOf(':') + 1);
            accepted.add(category.ruleId());
            accepted.add(shortId);
        }
        for (Node current = node; current != null; current = current.getParentNode().orElse(null)) {
            if (!(current instanceof com.github.javaparser.ast.nodeTypes.NodeWithAnnotations<?> annotated)) continue;
            Optional<com.github.javaparser.ast.expr.AnnotationExpr> suppress = annotated.getAnnotationByName("SuppressWarnings");
            if (suppress.isEmpty()) continue;
            for (com.github.javaparser.ast.expr.StringLiteralExpr value
                    : suppress.get().findAll(com.github.javaparser.ast.expr.StringLiteralExpr.class)) {
                String v = value.getValue().trim();
                String vShort = v.substring(v.lastIndexOf(':') + 1).trim();
                if (accepted.contains(v) || accepted.contains(vShort)) return true;
            }
        }
        return false;
    }

    private static void report(List<ForbiddenApiViolation> out, ForbiddenApiCatalog.ForbiddenCategory category,
                               String filePath, Node node, String target) {
        if (isSuppressed(node, category)) return;
        out.add(new ForbiddenApiViolation(filePath, line(node), ViolationType.UNSAFE_USAGE, target,
            category.name(), category.severity(), category.description(), category.replacement(),
            category.cweId(), category.cweName()));
    }
}
