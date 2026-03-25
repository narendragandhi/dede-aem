package com.dede.cloud;

import com.dede.domain.GraphService;
import com.dede.domain.model.CodeNode;
import com.dede.domain.model.NodeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Detects legacy JCR paths in AEM projects that are incompatible with Cloud Service.
 *
 * Scans:
 * - .content.xml files for path references
 * - Java code for hardcoded paths
 * - HTL/HTML templates for path references
 * - filter.xml for legacy path patterns
 */
@Component
public class LegacyPathDetector {

    private static final Logger log = LoggerFactory.getLogger(LegacyPathDetector.class);

    private final GraphService graphService;
    private final ForbiddenApiCatalog catalog;

    // Pattern to extract JCR paths from content
    private static final Pattern JCR_PATH_PATTERN = Pattern.compile(
        "\"(/(?:etc|var|content|apps|conf|libs)[^\"]*)\""
    );

    // Pattern for paths in Sling resource references
    private static final Pattern SLING_RESOURCE_PATTERN = Pattern.compile(
        "sling:resourceType\\s*=\\s*\"([^\"]+)\""
    );

    public LegacyPathDetector(GraphService graphService, ForbiddenApiCatalog catalog) {
        this.graphService = graphService;
        this.catalog = catalog;
    }

    /**
     * Scans a project for legacy path usage.
     */
    public LegacyPathReport scanProject(Path projectRoot) {
        List<LegacyPathViolation> violations = new ArrayList<>();

        // Scan .content.xml files
        violations.addAll(scanContentXml(projectRoot));

        // Scan Java files for hardcoded paths
        violations.addAll(scanJavaFiles(projectRoot));

        // Scan HTL files
        violations.addAll(scanHtlFiles(projectRoot));

        // Scan filter.xml files
        violations.addAll(scanFilterXml(projectRoot));

        // Group by legacy path
        Map<String, List<LegacyPathViolation>> violationsByPath = violations.stream()
            .collect(Collectors.groupingBy(LegacyPathViolation::legacyPath));

        return new LegacyPathReport(violations, violationsByPath);
    }

    /**
     * Scans .content.xml files for legacy paths.
     */
    private List<LegacyPathViolation> scanContentXml(Path projectRoot) {
        List<LegacyPathViolation> violations = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(projectRoot)) {
            paths.filter(Files::isRegularFile)
                 .filter(p -> p.toString().endsWith(".content.xml"))
                 .forEach(xmlFile -> {
                     try {
                         String content = Files.readString(xmlFile);
                         violations.addAll(findLegacyPathsInContent(
                             xmlFile.toString(), content, "JCR Content"));
                     } catch (IOException e) {
                         log.warn("Failed to read XML file: {}", xmlFile, e);
                     }
                 });
        } catch (IOException e) {
            log.error("Failed to scan content XML files: {}", projectRoot, e);
        }

        return violations;
    }

    /**
     * Scans Java files for hardcoded legacy paths.
     */
    private List<LegacyPathViolation> scanJavaFiles(Path projectRoot) {
        List<LegacyPathViolation> violations = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(projectRoot)) {
            paths.filter(Files::isRegularFile)
                 .filter(p -> p.toString().endsWith(".java"))
                 .forEach(javaFile -> {
                     try {
                         String content = Files.readString(javaFile);
                         violations.addAll(findLegacyPathsInContent(
                             javaFile.toString(), content, "Java Code"));
                     } catch (IOException e) {
                         log.warn("Failed to read Java file: {}", javaFile, e);
                     }
                 });
        } catch (IOException e) {
            log.error("Failed to scan Java files: {}", projectRoot, e);
        }

        return violations;
    }

    /**
     * Scans HTL files for legacy paths.
     */
    private List<LegacyPathViolation> scanHtlFiles(Path projectRoot) {
        List<LegacyPathViolation> violations = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(projectRoot)) {
            paths.filter(Files::isRegularFile)
                 .filter(p -> {
                     String name = p.toString().toLowerCase();
                     return name.endsWith(".html") || name.endsWith(".htl");
                 })
                 .forEach(htlFile -> {
                     try {
                         String content = Files.readString(htlFile);
                         violations.addAll(findLegacyPathsInContent(
                             htlFile.toString(), content, "HTL Template"));
                     } catch (IOException e) {
                         log.warn("Failed to read HTL file: {}", htlFile, e);
                     }
                 });
        } catch (IOException e) {
            log.error("Failed to scan HTL files: {}", projectRoot, e);
        }

        return violations;
    }

    /**
     * Scans filter.xml files for legacy path patterns.
     */
    private List<LegacyPathViolation> scanFilterXml(Path projectRoot) {
        List<LegacyPathViolation> violations = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(projectRoot)) {
            paths.filter(Files::isRegularFile)
                 .filter(p -> p.toString().endsWith("filter.xml"))
                 .forEach(filterFile -> {
                     try {
                         String content = Files.readString(filterFile);
                         violations.addAll(findLegacyPathsInFilterXml(
                             filterFile.toString(), content));
                     } catch (IOException e) {
                         log.warn("Failed to read filter.xml: {}", filterFile, e);
                     }
                 });
        } catch (IOException e) {
            log.error("Failed to scan filter.xml files: {}", projectRoot, e);
        }

        return violations;
    }

    /**
     * Finds legacy paths in content using the catalog.
     */
    private List<LegacyPathViolation> findLegacyPathsInContent(String filePath,
                                                                String content,
                                                                String sourceType) {
        List<LegacyPathViolation> violations = new ArrayList<>();

        // Extract all paths from the content
        Matcher matcher = JCR_PATH_PATTERN.matcher(content);
        int lineNumber = 1;

        while (matcher.find()) {
            String path = matcher.group(1);

            // Check against legacy path catalog
            Optional<ForbiddenApiCatalog.LegacyPath> legacyPath = catalog.checkPath(path);

            if (legacyPath.isPresent()) {
                // Calculate approximate line number
                int position = matcher.start();
                lineNumber = countNewlines(content, 0, position) + 1;

                violations.add(new LegacyPathViolation(
                    filePath,
                    lineNumber,
                    path,
                    legacyPath.get().path(),
                    legacyPath.get().migrationTarget(),
                    legacyPath.get().description(),
                    sourceType
                ));
            }
        }

        return violations;
    }

    /**
     * Finds legacy paths in filter.xml specifically.
     */
    private List<LegacyPathViolation> findLegacyPathsInFilterXml(String filePath, String content) {
        List<LegacyPathViolation> violations = new ArrayList<>();

        // Pattern for filter root paths: root="/etc/..."
        Pattern filterRootPattern = Pattern.compile("root=\"([^\"]+)\"");
        Matcher matcher = filterRootPattern.matcher(content);

        while (matcher.find()) {
            String path = matcher.group(1);
            Optional<ForbiddenApiCatalog.LegacyPath> legacyPath = catalog.checkPath(path);

            if (legacyPath.isPresent()) {
                int position = matcher.start();
                int lineNumber = countNewlines(content, 0, position) + 1;

                violations.add(new LegacyPathViolation(
                    filePath,
                    lineNumber,
                    path,
                    legacyPath.get().path(),
                    legacyPath.get().migrationTarget(),
                    "Filter.xml includes legacy path: " + legacyPath.get().description(),
                    "Package Filter"
                ));
            }
        }

        return violations;
    }

    /**
     * Counts newlines in a substring.
     */
    private int countNewlines(String content, int start, int end) {
        int count = 0;
        for (int i = start; i < end && i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    /**
     * Analyzes the graph for nodes with legacy paths.
     */
    public List<CodeNode> findNodesWithLegacyPaths() {
        return graphService.getAllNodes().stream()
            .filter(n -> {
                String path = n.getFilePath();
                if (path == null) return false;

                // Check if the file path indicates a legacy location
                return catalog.checkPath(path).isPresent();
            })
            .collect(Collectors.toList());
    }

    /**
     * Gets migration recommendations for all detected legacy paths.
     */
    public List<MigrationRecommendation> getMigrationRecommendations(LegacyPathReport report) {
        Map<String, MigrationRecommendation> recommendations = new LinkedHashMap<>();

        for (LegacyPathViolation violation : report.violations()) {
            String key = violation.legacyPath();

            if (!recommendations.containsKey(key)) {
                recommendations.put(key, new MigrationRecommendation(
                    violation.legacyPath(),
                    violation.migrationTarget(),
                    violation.description(),
                    new ArrayList<>()
                ));
            }

            recommendations.get(key).affectedFiles().add(
                new AffectedFile(violation.filePath(), violation.line(), violation.foundPath())
            );
        }

        return new ArrayList<>(recommendations.values());
    }

    // --- Record Types ---

    public record LegacyPathViolation(
        String filePath,
        int line,
        String foundPath,
        String legacyPath,
        String migrationTarget,
        String description,
        String sourceType
    ) {}

    public record LegacyPathReport(
        List<LegacyPathViolation> violations,
        Map<String, List<LegacyPathViolation>> violationsByPath
    ) {
        public int getTotalViolations() {
            return violations.size();
        }

        public Set<String> getUniqueLegacyPaths() {
            return violationsByPath.keySet();
        }

        public int getAffectedFilesCount() {
            return (int) violations.stream()
                .map(LegacyPathViolation::filePath)
                .distinct()
                .count();
        }
    }

    public record MigrationRecommendation(
        String legacyPath,
        String migrationTarget,
        String description,
        List<AffectedFile> affectedFiles
    ) {}

    public record AffectedFile(
        String filePath,
        int line,
        String foundPath
    ) {}
}
