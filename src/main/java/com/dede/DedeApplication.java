package com.dede;

import com.dede.discovery.ProjectScanner;
import com.dede.discovery.SourceParser;
import com.dede.discovery.OsgiLinker;
import com.dede.domain.DeltaAnalyzer;
import com.dede.domain.GraphService;
import com.dede.knowledge.GovernanceEngine;
import com.dede.intelligence.VulnerabilityService;
import com.dede.intelligence.GraphAgentSkills;
import com.dede.intelligence.CloudReadinessAnalyzer;
import com.dede.cloud.BpaReportGenerator;
import com.dede.cloud.ForbiddenApiScanner;
import com.dede.cloud.SarifReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

@SpringBootApplication
public class DedeApplication {

    private static final Logger log = LoggerFactory.getLogger(DedeApplication.class);
    private static final String VERSION = "2.0.0";

    public static void main(String[] args) {
        SpringApplication.run(DedeApplication.class, args);
    }

    @Bean
    public CommandLineRunner commandLineRunner(ProjectScanner scanner, SourceParser sourceParser,
                                             GraphService graphService, GovernanceEngine governance,
                                             VulnerabilityService security, GraphAgentSkills agent,
                                             OsgiLinker osgiLinker, DeltaAnalyzer deltaAnalyzer,
                                             CloudReadinessAnalyzer cloudAnalyzer, BpaReportGenerator bpaGenerator,
                                             ForbiddenApiScanner forbiddenApiScanner,
                                             ConfigurableApplicationContext context) {
        return args -> {
            if (args.length == 0) {
                // No args: stay up as the REST/GraphQL/Web UI server.
                printHelp();
                return;
            }

            if ("--help".equals(args[0]) || "-h".equals(args[0])) {
                printHelp();
                System.exit(SpringApplication.exit(context, () -> 0));
            }

            String projectPath = args[0];
            String rulesPath = null;
            String dotOutputPath = null;
            String analyzeNodeId = null;
            boolean checkSecurity = false;
            String profiles = "aem"; // Default
            String snapshotPath = null;
            String compareSnapshot = null;
            String bpaReportPath = null;
            String sarifOutputPath = null;
            boolean cloudReadiness = false;
            boolean watchMode = false;

            for (int i = 0; i < args.length; i++) {
                if ("--rules".equals(args[i]) && i + 1 < args.length) {
                    rulesPath = args[i + 1];
                }
                if ("--dot".equals(args[i]) && i + 1 < args.length) {
                    dotOutputPath = args[i + 1];
                }
                if ("--analyze".equals(args[i]) && i + 1 < args.length) {
                    analyzeNodeId = args[i + 1];
                }
                if ("--security".equals(args[i])) {
                    checkSecurity = true;
                }
                if ("--profiles".equals(args[i]) && i + 1 < args.length) {
                    profiles = args[i + 1];
                }
                if ("--snapshot".equals(args[i]) && i + 1 < args.length) {
                    snapshotPath = args[i + 1];
                }
                if ("--compare".equals(args[i]) && i + 1 < args.length) {
                    compareSnapshot = args[i + 1];
                }
                if ("--bpa-report".equals(args[i]) && i + 1 < args.length) {
                    bpaReportPath = args[i + 1];
                }
                if ("--sarif".equals(args[i]) && i + 1 < args.length) {
                    sarifOutputPath = args[i + 1];
                }
                if ("--cloud-readiness".equals(args[i])) {
                    cloudReadiness = true;
                }
                if ("--watch".equals(args[i])) {
                    watchMode = true;
                }
            }

            printBanner();
            log.info("Starting scan of project: {}", projectPath);
            log.info("Using profiles: {}", profiles);

            sourceParser.loadProfiles(profiles.split(","));
            scanner.scan(projectPath);

            // Link phase: connect bundles via imports/exports, link configs to services
            log.info("Running OSGi linker to connect bundles and configurations...");
            osgiLinker.link();

            if (analyzeNodeId != null) {
                log.info("Performing impact analysis for node: {}", analyzeNodeId);
                String analysis = agent.analyzeImpact(analyzeNodeId);
                log.info("Impact Analysis for '{}': {}", analyzeNodeId, analysis);
            }

            if (rulesPath != null) {
                governance.loadRules(new File(rulesPath));
                governance.validate(graphService.getGraph());
            }

            if (checkSecurity) {
                security.audit(graphService.getGraph());
            }

            if (dotOutputPath != null) {
                log.info("Exporting graph to DOT: {}", dotOutputPath);
                graphService.exportToDot(new File(dotOutputPath));
            }

            log.info("Scan Summary: {} nodes, {} edges", graphService.getNodeCount(), graphService.getEdgeCount());

            if (rulesPath != null) {
                governance.printViolations();
            }

            if (checkSecurity) {
                security.printReport();
            }

            // Delta Analysis
            if (snapshotPath != null) {
                log.info("Saving snapshot to: {}", snapshotPath);
                String savedPath = deltaAnalyzer.saveSnapshot(Path.of(snapshotPath));
                log.info("Snapshot saved: {}", savedPath);
            }

            if (compareSnapshot != null) {
                log.info("Comparing with snapshot: {}", compareSnapshot);
                var deltaReport = deltaAnalyzer.compareWithSnapshot(Path.of(compareSnapshot));
                log.info("\n{}", deltaAnalyzer.generateTextReport(deltaReport));
            }

            // Cloud Readiness Analysis
            if (cloudReadiness) {
                log.info("Running AEM Cloud Service readiness analysis...");
                var cloudReport = cloudAnalyzer.analyze();
                log.info("\n{}", cloudAnalyzer.generateTextReport(cloudReport));
            }

            // BPA Report Generation
            if (bpaReportPath != null) {
                log.info("Generating BPA-compatible report: {}", bpaReportPath);
                var cloudReport = cloudAnalyzer.analyze();
                String projectName = Path.of(projectPath).getFileName().toString();
                var bpaReport = bpaGenerator.generateReport(cloudReport, projectName);

                Path outputPath = Path.of(bpaReportPath);
                if (bpaReportPath.endsWith(".html")) {
                    bpaGenerator.exportToHtml(bpaReport, outputPath);
                } else {
                    bpaGenerator.exportToJson(bpaReport, outputPath);
                }
                log.info("BPA report saved to: {}", bpaReportPath);
            }

            // SARIF Report (for GitHub Code Scanning, VS Code Problems panel, SIEMs)
            if (sarifOutputPath != null) {
                log.info("Generating SARIF report: {}", sarifOutputPath);
                Path scanRoot = Path.of(projectPath);
                ForbiddenApiScanner.ScanResult scanResult = forbiddenApiScanner.scanProject(scanRoot);
                SarifReport sarif = new SarifReport(scanResult.violations(), scanRoot);

                ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
                try {
                    mapper.writeValue(new File(sarifOutputPath), sarif.toSarif());
                    log.info("SARIF report saved to: {}", sarifOutputPath);
                } catch (IOException e) {
                    log.error("Failed to write SARIF report to {}: {}", sarifOutputPath, e.getMessage());
                }
            }

            // AI Insights
            List<String> suggestions = agent.suggestRefactoring();
            if (suggestions.isEmpty()) {
                log.info("AI Refactoring Suggestions: Architecture looks solid!");
            } else {
                log.info("AI Refactoring Suggestions:");
                suggestions.forEach(s -> log.info("  - {}", s));
            }

            if (watchMode) {
                scanner.startWatching(projectPath);
            } else {
                System.exit(SpringApplication.exit(context, () -> 0));
            }
        };
    }

    private void printBanner() {
        log.info("");
        log.info("  ____           _            _                  ");
        log.info(" |  _ \\  ___  __| | ___      | | __ ___   ____ _ ");
        log.info(" | | | |/ _ \\/ _` |/ _ \\ ___ | |/ _` \\ \\ / / _` |");
        log.info(" | |_| |  __/ (_| |  __/|___|| | (_| |\\ V / (_| |");
        log.info(" |____/ \\___|\\__,_|\\___|     |_|\\__,_| \\_/ \\__,_|");
        log.info("");
        log.info("  Architectural Intelligence Engine v{}", VERSION);
        log.info("  Inspired by Mitko Kolev's 'dede'");
        log.info("");
        log.info("  Features: CALLS graph | GraphQL | D3.js UI | Delta Analysis");
        log.info("  =============================================================");
    }

    private void printHelp() {
        log.info("Usage: dede <project-path> [options]");
        log.info("Options:");
        log.info("  --profiles <p1,p2>  Comma-separated profiles (default: aem)");
        log.info("  --rules <path>      Path to dede-rules.json");
        log.info("  --security          Enable security reachability audit");
        log.info("  --dot <path.dot>    Export visual graph to DOT format");
        log.info("  --analyze <nodeId>  Perform impact analysis for a node");
        log.info("  --snapshot <dir>    Save graph snapshot to directory");
        log.info("  --compare <file>    Compare with previous snapshot");
        log.info("  --cloud-readiness   Run AEM Cloud Service readiness check");
        log.info("  --bpa-report <path> Generate BPA-compatible report (.json or .html)");
        log.info("  --sarif <path.sarif.json> Export forbidden-API findings as SARIF 2.1.0");
        log.info("  --watch             Start watching for file changes and update incrementally");
        log.info("");
        log.info("Web UI:      http://localhost:8080/");
        log.info("GraphQL:     http://localhost:8080/graphql");
        log.info("GraphiQL:    http://localhost:8080/graphiql");
        log.info("Swagger UI:  http://localhost:8080/swagger-ui.html");
    }
}
