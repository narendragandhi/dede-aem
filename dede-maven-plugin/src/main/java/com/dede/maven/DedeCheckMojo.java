package com.dede.maven;

import com.dede.cloud.BpaReportGenerator;
import com.dede.cloud.ForbiddenApiScanner;
import com.dede.cloud.ForbiddenApiScanner.ScanResult;
import com.dede.cloud.SarifReport;
import com.dede.discovery.OsgiLinker;
import com.dede.discovery.ProjectScanner;
import com.dede.discovery.SourceParser;
import com.dede.domain.GraphService;
import com.dede.intelligence.CloudReadinessAnalyzer;
import com.dede.intelligence.CloudReadinessAnalyzer.CloudIssue;
import com.dede.intelligence.CloudReadinessAnalyzer.CloudReadinessReport;
import com.dede.intelligence.VulnerabilityService;
import com.dede.knowledge.GovernanceEngine;
import com.dede.security.DependencyCveImporter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Runs Dede's AEM Cloud Service readiness and security analysis as part of a Maven
 * build, replacing the shell-scripted `java -jar dede.jar ... | grep CRITICAL` CI
 * pattern the README documents with a real, idiomatic Maven goal.
 *
 * Bootstraps a headless Spring context per execution (see {@link MojoBootstrapConfig}
 * for why this deliberately does not reuse DedeApplication directly) rather than
 * re-wiring the ~12-bean object graph by hand -- that graph is Spring's job, and
 * hand-wiring it here would silently drift out of sync as new beans are added.
 */
@Mojo(name = "check", defaultPhase = LifecyclePhase.VERIFY, threadSafe = false)
public class DedeCheckMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project.basedir}", required = true)
    private File projectPath;

    @Parameter(defaultValue = "aem")
    private String profiles;

    @Parameter
    private File rulesFile;

    @Parameter(defaultValue = "true")
    private boolean security;

    @Parameter
    private File dependencyCheckReport;

    @Parameter(defaultValue = "${project.build.directory}/dede-report.sarif.json")
    private File sarifOutputFile;

    @Parameter(defaultValue = "${project.build.directory}/dede-bpa-report.json")
    private File bpaReportFile;

    @Parameter(defaultValue = "true")
    private boolean failOnCritical;

    /**
     * Enables ratchet mode (the Sentinel-Twin gate): when set, the build fails only on
     * forbidden-API violations that are NOT already recorded in this baseline file.
     * Pre-existing debt stays green; every NEW legacy import or call fails the build.
     * Create/refresh the baseline deliberately with -Ddede.updateBaseline=true and
     * commit the file so CI compares against it.
     */
    @Parameter
    private File baselineFile;

    /** One-time baseline refresh: rewrite {@code baselineFile} from current findings instead of gating. */
    @Parameter(defaultValue = "false", property = "dede.updateBaseline")
    private boolean updateBaseline;

    /**
     * Root used to relativize violation paths in baseline keys. Defaults to Maven's
     * {@code maven.multiModuleProjectDirectory}, so reactor modules produce distinct,
     * stable keys ({@code app-core/src/...} vs {@code app-web/src/...}) against one
     * shared baseline. Falls back to {@link #projectPath} when unavailable (CLI runs,
     * direct instantiation).
     */
    @Parameter(defaultValue = "${maven.multiModuleProjectDirectory}")
    private File multiModuleRoot;

    @Parameter(defaultValue = "false", property = "dede.skip")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Dede check skipped (dede.skip=true)");
            return;
        }

        ConfigurableApplicationContext context = new SpringApplicationBuilder(MojoBootstrapConfig.class)
            .web(WebApplicationType.NONE)
            .run();

        try {
            runAnalysis(context);
        } catch (MojoFailureException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("Dede analysis failed: " + e.getMessage(), e);
        } finally {
            context.close();
        }
    }

    private void runAnalysis(ConfigurableApplicationContext context) throws Exception {
        String projectPathStr = projectPath.getAbsolutePath();
        getLog().info("Dede: scanning " + projectPathStr + " (profiles=" + profiles + ")");

        SourceParser sourceParser = context.getBean(SourceParser.class);
        ProjectScanner scanner = context.getBean(ProjectScanner.class);
        OsgiLinker osgiLinker = context.getBean(OsgiLinker.class);
        GraphService graphService = context.getBean(GraphService.class);
        GovernanceEngine governance = context.getBean(GovernanceEngine.class);
        VulnerabilityService securityService = context.getBean(VulnerabilityService.class);
        CloudReadinessAnalyzer cloudAnalyzer = context.getBean(CloudReadinessAnalyzer.class);
        BpaReportGenerator bpaGenerator = context.getBean(BpaReportGenerator.class);
        ForbiddenApiScanner forbiddenApiScanner = context.getBean(ForbiddenApiScanner.class);
        DependencyCveImporter cveImporter = context.getBean(DependencyCveImporter.class);

        sourceParser.loadProfiles(profiles.split(","));
        scanner.scan(projectPathStr);
        osgiLinker.link();

        if (rulesFile != null) {
            governance.loadRules(rulesFile);
            governance.validate(graphService.getGraph());
            governance.printViolations();
        }

        if (dependencyCheckReport != null) {
            getLog().info("Importing CVE findings from " + dependencyCheckReport);
            cveImporter.importReport(dependencyCheckReport.toPath());
        }

        if (security) {
            securityService.audit(graphService.getGraph());
            securityService.printReport();
        }

        getLog().info("Dede: scanned " + graphService.getNodeCount() + " nodes, "
            + graphService.getEdgeCount() + " edges");

        CloudReadinessReport cloudReport = cloudAnalyzer.analyze();
        long critical = cloudReport.getIssues().stream()
            .filter(i -> i.getSeverity() == CloudReadinessAnalyzer.Severity.CRITICAL)
            .count();
        long high = cloudReport.getIssues().stream()
            .filter(i -> i.getSeverity() == CloudReadinessAnalyzer.Severity.HIGH)
            .count();
        getLog().info("Dede: cloud readiness score " + cloudReport.getScore()
            + "% (" + critical + " CRITICAL, " + high + " HIGH)");

        // Scanned once here so the SARIF export and the ratchet gate see exactly the
        // same findings -- and so a scan failure now fails the build instead of being
        // swallowed by the report writer's warn-and-continue handling.
        ScanResult scanResult = forbiddenApiScanner.scanProject(Path.of(projectPathStr));
        getLog().info("Dede: " + scanResult.getTotalViolations() + " forbidden-API finding(s)");

        writeBpaReport(cloudReport, bpaGenerator);
        writeSarifReport(scanResult);

        // Both gates always evaluate; one combined failure reports everything found,
        // instead of fail-fast hiding ratchet findings behind CRITICAL cloud issues.
        List<String> gateFailures = new java.util.ArrayList<>();
        String criticalFailure = checkCritical(cloudReport);
        if (criticalFailure != null) {
            gateFailures.add(criticalFailure);
        }
        String ratchetFailure = enforceRatchet(scanResult);
        if (ratchetFailure != null) {
            gateFailures.add(ratchetFailure);
        }
        if (!gateFailures.isEmpty()) {
            throw new MojoFailureException(String.join("\n", gateFailures));
        }
    }

    /**
     * Returns the failure message for CRITICAL cloud-readiness issues, or null when the
     * gate passes. Extracted from runAnalysis() so it's directly unit-testable against a
     * hand-built CloudReadinessReport, independent of the full scan pipeline and Spring
     * bootstrap -- see DedeCheckMojoTest.
     */
    String checkCritical(CloudReadinessReport cloudReport) {
        if (!failOnCritical) {
            return null;
        }
        List<CloudIssue> criticalIssues = cloudReport.getIssues().stream()
            .filter(i -> i.getSeverity() == CloudReadinessAnalyzer.Severity.CRITICAL)
            .toList();
        if (criticalIssues.isEmpty()) {
            return null;
        }
        StringBuilder message = new StringBuilder(criticalIssues.size() + " CRITICAL cloud-readiness issue(s) found:\n");
        criticalIssues.forEach(i -> message.append("  - ").append(i.getMessage()).append('\n'));
        return message.toString();
    }

    /** Test-only: production code sets this via the Mojo's @Parameter injection. */
    void setFailOnCritical(boolean failOnCritical) {
        this.failOnCritical = failOnCritical;
    }

    private void writeBpaReport(CloudReadinessReport cloudReport, BpaReportGenerator bpaGenerator) {
        try {
            var report = bpaGenerator.generateReport(cloudReport, projectPath.getName());
            bpaReportFile.getParentFile().mkdirs();
            bpaGenerator.exportToJson(report, bpaReportFile.toPath());
            getLog().info("Dede: BPA report written to " + bpaReportFile);
        } catch (Exception e) {
            getLog().warn("Failed to write BPA report: " + e.getMessage());
        }
    }

    private void writeSarifReport(ScanResult scanResult) {
        try {
            Path root = Path.of(projectPath.getAbsolutePath());
            SarifReport sarif = new SarifReport(scanResult.violations(), root);

            ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
            sarifOutputFile.getParentFile().mkdirs();
            mapper.writeValue(sarifOutputFile, sarif.toSarif());
            getLog().info("Dede: SARIF report written to " + sarifOutputFile);
        } catch (Exception e) {
            getLog().warn("Failed to write SARIF report: " + e, e);
        }
    }

    // --- Sentinel-Twin ratchet gate ---

    /**
     * Returns the failure message for forbidden-API violations absent from
     * {@link #baselineFile}, or null when the gate passes. Infrastructure problems
     * (missing/malformed/unreadable baseline) still throw immediately -- they mean the
     * gate cannot evaluate at all, which is distinct from findings it reports.
     * Package-private for direct unit testing -- see DedeCheckMojoTest.
     */
    String enforceRatchet(ScanResult scanResult) throws MojoFailureException {
        if (baselineFile == null) {
            if (updateBaseline) {
                getLog().warn("Dede ratchet: -Ddede.updateBaseline=true has no effect without <baselineFile>");
            }
            return null;
        }
        Set<String> current = new TreeSet<>();
        Path anchor = multiModuleRoot != null
            ? multiModuleRoot.toPath()
            : Path.of(projectPath.getAbsolutePath());
        scanResult.violations().forEach(v -> current.add(baselineKey(v, anchor)));

        if (updateBaseline) {
            writeBaseline(current);
            return null;
        }

        if (!baselineFile.isFile()) {
            throw new MojoFailureException(
                "Dede baseline file not found: " + baselineFile.getAbsolutePath()
                + "\nCreate it once with: mvn ... dede:check -Ddede.updateBaseline=true"
                + "\nThen commit " + baselineFile.getName() + " so CI gates against it.");
        }

        Set<String> known = readBaselineKeys();
        List<String> newViolations = current.stream().filter(k -> !known.contains(k)).toList();
        if (newViolations.isEmpty()) {
            long fixed = known.size() - current.size();
            getLog().info("Dede ratchet: no new forbidden-API violations (" + fixed
                + " previously-baselined finding(s) now resolved)");
            return null;
        }

        StringBuilder message = new StringBuilder(newViolations.size()
            + " NEW forbidden-API violation(s) introduced (Sentinel-Twin gate):\n");
        newViolations.forEach(k -> message.append("  - ").append(k).append('\n'));
        message.append("Fix these legacy API usages, or refresh the baseline deliberately with")
            .append(" -Ddede.updateBaseline=true after review.");
        return message.toString();
    }

    /**
     * Stable violation identity for baseline comparison. Line numbers are deliberately
     * excluded so refactors above a violation don't masquerade as a new finding, and
     * file paths are relativized to the scanned project root so a baseline committed
     * from a developer machine matches CI checkout paths.
     */
    static String baselineKey(ForbiddenApiScanner.ForbiddenApiViolation v, Path projectRoot) {
        return v.type() + "|" + v.target() + "|" + portablePath(v.filePath(), projectRoot);
    }

    private static String portablePath(String filePath, Path projectRoot) {
        try {
            Path p = Path.of(filePath);
            if (p.isAbsolute()) {
                return projectRoot.relativize(p).toString();
            }
        } catch (IllegalArgumentException e) {
            // Not relativizable (e.g. mismatched roots); fall back to the raw path.
        }
        return filePath;
    }

    private void writeBaseline(Set<String> keys) throws MojoFailureException {
        try {
            ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
            ObjectNode root = mapper.createObjectNode();
            root.put("generatedAt", Instant.now().toString());
            root.put("violationCount", keys.size());
            ArrayNode violations = root.putArray("violations");
            keys.forEach(violations::add);
            baselineFile.getParentFile().mkdirs();
            mapper.writeValue(baselineFile, root);
            getLog().info("Dede ratchet: baseline written with " + keys.size()
                + " finding(s) to " + baselineFile.getAbsolutePath());
        } catch (IOException e) {
            throw new MojoFailureException("Failed to write Dede baseline: " + e.getMessage(), e);
        }
    }

    private Set<String> readBaselineKeys() throws MojoFailureException {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(baselineFile);
            JsonNode violations = root.get("violations");
            if (violations == null || !violations.isArray()) {
                throw new MojoFailureException(
                    "Malformed Dede baseline (missing 'violations' array): "
                    + baselineFile.getAbsolutePath());
            }
            Set<String> known = new TreeSet<>();
            violations.forEach(n -> known.add(n.asText()));
            return known;
        } catch (IOException e) {
            throw new MojoFailureException("Failed to read Dede baseline: " + e.getMessage(), e);
        }
    }

    /** Test-only: production code sets these via @Parameter injection. */
    void setProjectPath(File projectPath) {
        this.projectPath = projectPath;
    }

    void setMultiModuleRoot(File multiModuleRoot) {
        this.multiModuleRoot = multiModuleRoot;
    }

    void setBaselineFile(File baselineFile) {
        this.baselineFile = baselineFile;
    }

    void setUpdateBaseline(boolean updateBaseline) {
        this.updateBaseline = updateBaseline;
    }
}
