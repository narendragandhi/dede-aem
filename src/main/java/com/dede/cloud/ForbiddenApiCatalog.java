package com.dede.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Catalog of forbidden APIs and deprecated patterns for AEM Cloud Service.
 *
 * This catalog is loaded from forbidden-apis.json and provides methods to:
 * - Check if a class/package is forbidden
 * - Check if a method call is deprecated
 * - Get replacement recommendations
 * - Categorize issues by severity
 */
@Component
public class ForbiddenApiCatalog {

    private static final Logger log = LoggerFactory.getLogger(ForbiddenApiCatalog.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final List<ForbiddenCategory> categories = new ArrayList<>();
    private final List<LegacyPath> legacyPaths = new ArrayList<>();
    private final List<DeprecatedAnnotation> deprecatedAnnotations = new ArrayList<>();
    private final List<DeprecatedOsgiPid> deprecatedOsgiPids = new ArrayList<>();
    private final List<CloudManagedOsgiPid> cloudManagedOsgiPids = new ArrayList<>();

    // Pre-compiled patterns for efficient matching
    private final Map<String, Pattern> packagePatterns = new HashMap<>();

    @PostConstruct
    public void init() {
        loadCatalog();
    }

    /**
     * Loads the forbidden API catalog from the JSON resource file.
     */
    private void loadCatalog() {
        try (InputStream is = new ClassPathResource("forbidden-apis.json").getInputStream()) {
            JsonNode root = objectMapper.readTree(is);

            // Load categories
            JsonNode categoriesNode = root.get("categories");
            if (categoriesNode != null && categoriesNode.isArray()) {
                for (JsonNode categoryNode : categoriesNode) {
                    ForbiddenCategory category = parseCategory(categoryNode);
                    categories.add(category);
                }
            }

            // Load legacy paths
            JsonNode pathsNode = root.get("legacyPaths");
            if (pathsNode != null && pathsNode.isArray()) {
                for (JsonNode pathNode : pathsNode) {
                    PathSeverity severity = PathSeverity.MAJOR; // Default
                    if (pathNode.has("severity")) {
                        severity = PathSeverity.valueOf(pathNode.get("severity").asText());
                    }
                    legacyPaths.add(new LegacyPath(
                        pathNode.get("path").asText(),
                        pathNode.has("migrationTarget") && !pathNode.get("migrationTarget").isNull()
                            ? pathNode.get("migrationTarget").asText() : null,
                        pathNode.get("description").asText(),
                        severity
                    ));
                }
            }

            // Load deprecated OSGi PIDs
            JsonNode deprecatedPidsNode = root.get("deprecatedOsgiPids");
            if (deprecatedPidsNode != null && deprecatedPidsNode.isArray()) {
                for (JsonNode pidNode : deprecatedPidsNode) {
                    PathSeverity severity = PathSeverity.MAJOR;
                    if (pidNode.has("severity")) {
                        severity = PathSeverity.valueOf(pidNode.get("severity").asText());
                    }
                    deprecatedOsgiPids.add(new DeprecatedOsgiPid(
                        pidNode.get("pid").asText(),
                        severity,
                        pidNode.get("description").asText()
                    ));
                }
            }

            // Load cloud-managed OSGi PIDs
            JsonNode cloudManagedPidsNode = root.get("cloudManagedOsgiPids");
            if (cloudManagedPidsNode != null && cloudManagedPidsNode.isArray()) {
                for (JsonNode pidNode : cloudManagedPidsNode) {
                    cloudManagedOsgiPids.add(new CloudManagedOsgiPid(
                        pidNode.get("pid").asText(),
                        pidNode.get("description").asText()
                    ));
                }
            }

            // Load deprecated annotations
            JsonNode annotationsNode = root.get("deprecatedAnnotations");
            if (annotationsNode != null && annotationsNode.isArray()) {
                for (JsonNode annotationNode : annotationsNode) {
                    deprecatedAnnotations.add(new DeprecatedAnnotation(
                        annotationNode.get("annotation").asText(),
                        annotationNode.get("replacement").asText(),
                        annotationNode.get("description").asText()
                    ));
                }
            }

            // Pre-compile package patterns
            for (ForbiddenCategory category : categories) {
                for (String packagePattern : category.packages()) {
                    String regex = packagePattern
                        .replace(".", "\\.")
                        .replace("*", ".*");
                    packagePatterns.put(packagePattern, Pattern.compile("^" + regex));
                }
            }

            log.info("Loaded forbidden API catalog: {} categories, {} legacy paths, {} deprecated annotations, {} deprecated PIDs, {} cloud-managed PIDs",
                categories.size(), legacyPaths.size(), deprecatedAnnotations.size(),
                deprecatedOsgiPids.size(), cloudManagedOsgiPids.size());

        } catch (IOException e) {
            log.error("Failed to load forbidden-apis.json", e);
        }
    }

    private ForbiddenCategory parseCategory(JsonNode node) {
        String name = node.get("name").asText();
        String description = node.get("description").asText();
        Severity severity = Severity.valueOf(node.get("severity").asText());
        String replacement = node.has("replacement") ? node.get("replacement").asText() : null;

        List<String> packages = parseStringArray(node.get("packages"));
        List<String> classes = parseStringArray(node.get("classes"));
        List<String> methods = parseStringArray(node.get("methods"));
        List<String> annotations = parseStringArray(node.get("annotations"));

        return new ForbiddenCategory(name, description, severity, replacement,
            packages, classes, methods, annotations);
    }

    private List<String> parseStringArray(JsonNode arrayNode) {
        List<String> result = new ArrayList<>();
        if (arrayNode != null && arrayNode.isArray()) {
            for (JsonNode item : arrayNode) {
                result.add(item.asText());
            }
        }
        return result;
    }

    // --- Query Methods ---

    /**
     * Checks if a fully qualified class name matches any forbidden pattern.
     */
    public Optional<ForbiddenMatch> checkClass(String fullyQualifiedClassName) {
        for (ForbiddenCategory category : categories) {
            // Check exact class match
            if (category.classes().contains(fullyQualifiedClassName)) {
                return Optional.of(new ForbiddenMatch(
                    category.name(),
                    category.severity(),
                    category.description(),
                    category.replacement(),
                    MatchType.EXACT_CLASS
                ));
            }

            // Check package pattern match
            for (String packagePattern : category.packages()) {
                Pattern pattern = packagePatterns.get(packagePattern);
                if (pattern != null && pattern.matcher(fullyQualifiedClassName).matches()) {
                    return Optional.of(new ForbiddenMatch(
                        category.name(),
                        category.severity(),
                        category.description(),
                        category.replacement(),
                        MatchType.PACKAGE_PATTERN
                    ));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Checks if a method name is forbidden (any-class context, no false-positive filtering).
     * For supply-chain rules that scope methods to specific classes, use
     * {@link #checkClassScopedMethod(String, String)} instead.
     */
    public Optional<ForbiddenMatch> checkMethod(String methodName) {
        for (ForbiddenCategory category : categories) {
            // Only match method-only rules (categories with no class restriction)
            if (category.methods().contains(methodName) && category.classes().isEmpty()) {
                return Optional.of(new ForbiddenMatch(
                    category.name(),
                    category.severity(),
                    category.description(),
                    category.replacement(),
                    MatchType.METHOD
                ));
            }
        }
        return Optional.empty();
    }

    /**
     * Checks if a method call is forbidden when called on a specific class.
     * This is the precise check for supply-chain rules that scope methods to classes,
     * e.g. "exec" is only forbidden on Runtime/ProcessBuilder, not on arbitrary objects.
     *
     * @param callerSimpleName simple class name of the object the method is called on
     *                         (e.g. "Runtime", "ProcessBuilder", "URLClassLoader")
     * @param methodName       the method being invoked
     */
    public Optional<ForbiddenMatch> checkClassScopedMethod(String callerSimpleName, String methodName) {
        for (ForbiddenCategory category : categories) {
            if (!category.methods().contains(methodName) || category.classes().isEmpty()) {
                continue;
            }
            // Check if the caller's simple name or FQN matches any class in this category
            boolean classMatches = category.classes().stream().anyMatch(fqn -> {
                String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
                return simple.equals(callerSimpleName) || fqn.equals(callerSimpleName);
            });
            if (classMatches) {
                return Optional.of(new ForbiddenMatch(
                    category.name(),
                    category.severity(),
                    category.description(),
                    category.replacement(),
                    MatchType.CLASS_SCOPED_METHOD
                ));
            }
        }
        return Optional.empty();
    }

    /**
     * Checks if a method call on a specific class is forbidden.
     */
    public Optional<ForbiddenMatch> checkMethodCall(String className, String methodName) {
        // Try class-scoped method match first (avoids false positives)
        Optional<ForbiddenMatch> scopedMatch = checkClassScopedMethod(className, methodName);
        if (scopedMatch.isPresent()) {
            return scopedMatch;
        }

        // Fall back to any-class method match (unrestricted methods)
        Optional<ForbiddenMatch> methodMatch = checkMethod(methodName);
        if (methodMatch.isPresent()) {
            return methodMatch;
        }

        // Then check if the class itself is forbidden (all method calls forbidden)
        return checkClass(className);
    }

    /**
     * Checks if an annotation is deprecated.
     */
    public Optional<DeprecatedAnnotation> checkAnnotation(String annotationName) {
        return deprecatedAnnotations.stream()
            .filter(a -> a.annotation().equals(annotationName))
            .findFirst();
    }

    /**
     * Checks if a JCR path is a legacy path that needs migration.
     */
    public Optional<LegacyPath> checkPath(String jcrPath) {
        return legacyPaths.stream()
            .filter(lp -> jcrPath.startsWith(lp.path()))
            .findFirst();
    }

    /**
     * Gets all forbidden API categories.
     */
    public List<ForbiddenCategory> getAllCategories() {
        return Collections.unmodifiableList(categories);
    }

    /**
     * Gets categories by severity.
     */
    public List<ForbiddenCategory> getCategoriesBySeverity(Severity severity) {
        return categories.stream()
            .filter(c -> c.severity() == severity)
            .collect(Collectors.toList());
    }

    /**
     * Gets all legacy paths.
     */
    public List<LegacyPath> getAllLegacyPaths() {
        return Collections.unmodifiableList(legacyPaths);
    }

    /**
     * Checks if an OSGi PID is deprecated.
     */
    public Optional<DeprecatedOsgiPid> checkDeprecatedPid(String pid) {
        return deprecatedOsgiPids.stream()
            .filter(p -> pid.equals(p.pid()) || pid.startsWith(p.pid() + "~") || pid.startsWith(p.pid() + "-"))
            .findFirst();
    }

    /**
     * Checks if an OSGi PID is cloud-managed (cannot be customized).
     */
    public Optional<CloudManagedOsgiPid> checkCloudManagedPid(String pid) {
        return cloudManagedOsgiPids.stream()
            .filter(p -> pid.equals(p.pid()) || pid.startsWith(p.pid() + "~") || pid.startsWith(p.pid() + "-"))
            .findFirst();
    }

    /**
     * Gets all deprecated OSGi PIDs.
     */
    public List<DeprecatedOsgiPid> getAllDeprecatedOsgiPids() {
        return Collections.unmodifiableList(deprecatedOsgiPids);
    }

    /**
     * Gets all cloud-managed OSGi PIDs.
     */
    public List<CloudManagedOsgiPid> getAllCloudManagedOsgiPids() {
        return Collections.unmodifiableList(cloudManagedOsgiPids);
    }

    /**
     * Gets all deprecated annotations.
     */
    public List<DeprecatedAnnotation> getAllDeprecatedAnnotations() {
        return Collections.unmodifiableList(deprecatedAnnotations);
    }

    /**
     * Gets all forbidden class names (for quick lookup during scanning).
     */
    public Set<String> getAllForbiddenClasses() {
        return categories.stream()
            .flatMap(c -> c.classes().stream())
            .collect(Collectors.toSet());
    }

    /**
     * Gets all forbidden method names.
     */
    public Set<String> getAllForbiddenMethods() {
        return categories.stream()
            .flatMap(c -> c.methods().stream())
            .collect(Collectors.toSet());
    }

    /**
     * Gets all forbidden package patterns.
     */
    public Set<String> getAllForbiddenPackagePatterns() {
        return categories.stream()
            .flatMap(c -> c.packages().stream())
            .collect(Collectors.toSet());
    }

    // --- Record Types ---

    public enum Severity {
        INFO, MEDIUM, HIGH, CRITICAL
    }

    public enum MatchType {
        EXACT_CLASS, PACKAGE_PATTERN, METHOD, CLASS_SCOPED_METHOD
    }

    public record ForbiddenCategory(
        String name,
        String description,
        Severity severity,
        String replacement,
        List<String> packages,
        List<String> classes,
        List<String> methods,
        List<String> annotations
    ) {}

    public record ForbiddenMatch(
        String categoryName,
        Severity severity,
        String description,
        String replacement,
        MatchType matchType
    ) {}

    public record LegacyPath(
        String path,
        String migrationTarget,
        String description,
        PathSeverity severity
    ) {}

    public record DeprecatedOsgiPid(
        String pid,
        PathSeverity severity,
        String description
    ) {}

    public record CloudManagedOsgiPid(
        String pid,
        String description
    ) {}

    /**
     * Severity levels for paths - maps to OakPal Severity.
     */
    public enum PathSeverity {
        SEVERE,  // Forbidden, will fail deployment
        MAJOR,   // Requires migration
        MINOR    // Deprecated, should migrate
    }

    public record DeprecatedAnnotation(
        String annotation,
        String replacement,
        String description
    ) {}
}
