package com.dede.maven;

import com.dede.cloud.BpaReportGenerator;
import com.dede.cloud.ForbiddenApiScanner;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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
import java.nio.file.Path;
import java.util.List;

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

        writeBpaReport(cloudReport, bpaGenerator);
        writeSarifReport(projectPathStr, forbiddenApiScanner);

        checkCritical(cloudReport);
    }

    /**
     * Extracted from runAnalysis() so it's directly unit-testable against a
     * hand-built CloudReadinessReport, independent of the full scan pipeline and
     * Spring bootstrap -- see DedeCheckMojoTest.
     */
    void checkCritical(CloudReadinessReport cloudReport) throws MojoFailureException {
        if (!failOnCritical) {
            return;
        }
        List<CloudIssue> criticalIssues = cloudReport.getIssues().stream()
            .filter(i -> i.getSeverity() == CloudReadinessAnalyzer.Severity.CRITICAL)
            .toList();
        if (criticalIssues.isEmpty()) {
            return;
        }
        StringBuilder message = new StringBuilder(criticalIssues.size() + " CRITICAL cloud-readiness issue(s) found:\n");
        criticalIssues.forEach(i -> message.append("  - ").append(i.getMessage()).append('\n'));
        throw new MojoFailureException(message.toString());
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

    private void writeSarifReport(String projectPathStr, ForbiddenApiScanner forbiddenApiScanner) {
        try {
            Path root = Path.of(projectPathStr);
            var scanResult = forbiddenApiScanner.scanProject(root);
            SarifReport sarif = new SarifReport(scanResult.violations(), root);

            ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
            sarifOutputFile.getParentFile().mkdirs();
            mapper.writeValue(sarifOutputFile, sarif.toSarif());
            getLog().info("Dede: SARIF report written to " + sarifOutputFile);
        } catch (Exception e) {
            getLog().warn("Failed to write SARIF report: " + e, e);
        }
    }
}
