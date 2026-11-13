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
import com.dede.cloud.CloudFindings;
import com.dede.cloud.CloudFindingsService;
import com.dede.cloud.SarifReport;
import com.dede.security.DependencyCveImporter;
import com.dede.runtime.OtelConfigEmitter;
import com.dede.runtime.OtlpTraceReader;
import com.dede.runtime.RuntimeOverlay;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
                                             CloudFindingsService cloudFindings,
                                             DependencyCveImporter cveImporter,
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
            String suggestionsOutputPath = null;
            String dependencyCheckReportPath = null;
            boolean cloudReadiness = false;
            boolean watchMode = false;
            String jsonOutputPath = null;
            String runtimeTracesPath = null;
            String otelConfigPath = null;
            String failOn = null;  // gate threshold; null = no gate
            boolean extendedChecks = false;

            for (int i = 1; i < args.length; i++) {
                if (args[i].startsWith("--") && !KNOWN_FLAGS.contains(args[i])) {
                    log.warn("Unknown option '{}' ignored. Run with --help for the supported options.", args[i]);
                }
                if ("--json".equals(args[i]) && i + 1 < args.length) {
                    jsonOutputPath = args[i + 1];
                }
                if ("--runtime-traces".equals(args[i]) && i + 1 < args.length) {
                    runtimeTracesPath = args[i + 1];
                }
                if ("--emit-otel-config".equals(args[i]) && i + 1 < args.length) {
                    otelConfigPath = args[i + 1];
                }
                if ("--fail-on-critical".equals(args[i]) && failOn == null) {
                    failOn = "CRITICAL";
                }
                if ("--fail-on".equals(args[i])) {
                    try {
                        failOn = CloudFindings.parseThreshold(i + 1 < args.length ? args[i + 1] : null);
                    } catch (IllegalArgumentException e) {
                        // A mistyped gate must not run as "no gate".
                        log.error("--fail-on: {}", e.getMessage());
                        System.exit(SpringApplication.exit(context, () -> 2));
                    }
                }
                if ("--extended-checks".equals(args[i])) {
                    extendedChecks = true;
                }
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
                if ("--suggestions".equals(args[i]) && i + 1 < args.length) {
                    suggestionsOutputPath = args[i + 1];
                }
                if ("--dependency-check-report".equals(args[i]) && i + 1 < args.length) {
                    dependencyCheckReportPath = args[i + 1];
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

            if (dependencyCheckReportPath != null) {
                log.info("Importing CVE findings from dependency-check report: {}", dependencyCheckReportPath);
                cveImporter.importReport(Path.of(dependencyCheckReportPath));
            }

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

            // One set of findings (catalog scan + graph checks), shared by the SARIF export, the
            // JSON report and --fail-on / --fail-on-critical -- the same set the Maven plugin gates on.
            Path scanRoot = Path.of(projectPath);
            boolean needFindings = sarifOutputPath != null || jsonOutputPath != null || failOn != null
                || runtimeTracesPath != null || otelConfigPath != null;
            CloudFindings findings = needFindings ? cloudFindings.collect(scanRoot, extendedChecks) : null;

            // Runtime overlay: OpenTelemetry traces joined to the code graph.
            RuntimeOverlay runtime = null;
            if (runtimeTracesPath != null) {
                try {
                    runtime = RuntimeOverlay.apply(OtlpTraceReader.read(Path.of(runtimeTracesPath)), graphService);
                    log.info("Runtime traces: {} span(s), {} matched to {} project class(es), {} without code attributes, "
                            + "{} class(es) outside the scanned code",
                        runtime.totalSpans(), runtime.matchedSpans(), runtime.matchedClassCount(), runtime.unattributedSpans(), runtime.unmatched().size());
                    if (runtime.matchedClassCount() == 0) {
                        log.warn("Runtime traces: no span could be tied to project code. Spans need code.namespace/"
                            + "code.function attributes -- generate an agent config with --emit-otel-config.");
                    }
                    var hot = runtime.hotFindings(findings.all());
                    if (!hot.isEmpty()) {
                        log.info("Findings in code that ran ({}), most-called first:", hot.size());
                        hot.stream().limit(10).forEach(e -> log.info("  {} calls  {} [{}] {}", e.getValue(),
                            e.getKey().severity(), e.getKey().category(), e.getKey().file()));
                    }
                } catch (IOException | RuntimeException e) {
                    // A CI step relying on the overlay must not silently get a report without it.
                    log.error("Failed to read runtime traces from {}: {}", runtimeTracesPath, e.getMessage());
                    System.exit(SpringApplication.exit(context, () -> 2));
                }
            }

            if (otelConfigPath != null) {
                int maxClasses = 50;
                var selections = OtelConfigEmitter.select(graphService, findings.all(), maxClasses);
                if (selections.size() == maxClasses) {
                    log.warn("OpenTelemetry config: capped at {} classes to keep agent overhead down; classes with "
                        + "findings were picked first, then entry points, then by fan-in.", maxClasses);
                }
                try {
                    java.nio.file.Files.writeString(Path.of(otelConfigPath), OtelConfigEmitter.render(selections));
                    log.info("OpenTelemetry agent config for {} class(es) written to {}", selections.size(), otelConfigPath);
                } catch (IOException e) {
                    log.error("Failed to write OpenTelemetry config to {}: {}", otelConfigPath, e.getMessage());
                    System.exit(SpringApplication.exit(context, () -> 2));
                }
            }

            // SARIF Report (for GitHub Code Scanning, VS Code Problems panel, SIEMs)
            if (sarifOutputPath != null) {
                log.info("Generating SARIF report: {}", sarifOutputPath);
                SarifReport sarif = new SarifReport(findings.forbiddenApis().violations(), scanRoot);

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

            if (suggestionsOutputPath != null) {
                log.info("Writing refactoring suggestions: {}", suggestionsOutputPath);
                ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
                try {
                    mapper.writeValue(new File(suggestionsOutputPath), suggestions);
                } catch (IOException e) {
                    log.error("Failed to write refactoring suggestions to {}: {}", suggestionsOutputPath, e.getMessage());
                }
            }

            if (jsonOutputPath != null) {
                log.info("Writing JSON report: {}", jsonOutputPath);
                Map<String, Object> report = buildJsonReport(projectPath, graphService, findings, suggestions, runtime);
                ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
                try {
                    mapper.writeValue(new File(jsonOutputPath), report);
                } catch (IOException e) {
                    // A CI gate reading this file must not silently see "no findings".
                    log.error("Failed to write JSON report to {}: {}", jsonOutputPath, e.getMessage());
                    System.exit(SpringApplication.exit(context, () -> 2));
                }
            }

            int exitCode = 0;
            String gateFailure = failOn != null ? findings.failureMessage(failOn) : null;
            if (gateFailure != null) {
                log.error("Failing (--fail-on {}): {}", failOn, gateFailure);
                exitCode = 1;
            }

            if (watchMode) {
                scanner.startWatching(projectPath);
            } else {
                final int code = exitCode;
                System.exit(SpringApplication.exit(context, () -> code));
            }
        };
    }

    private static final Set<String> KNOWN_FLAGS = Set.of(
        "--help", "-h", "--rules", "--dot", "--analyze", "--security", "--profiles", "--snapshot",
        "--compare", "--bpa-report", "--sarif", "--suggestions", "--dependency-check-report",
        "--cloud-readiness", "--watch", "--json", "--fail-on-critical", "--fail-on", "--extended-checks",
        "--runtime-traces", "--emit-otel-config");

    private static Map<String, Object> buildJsonReport(String projectPath, GraphService graphService,
                                                       CloudFindings findings, List<String> suggestions,
                                                       RuntimeOverlay runtime) {
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("nodes", graphService.getNodeCount());
        graph.put("edges", graphService.getEdgeCount());

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("tool", "dede");
        report.put("version", VERSION);
        report.put("project", projectPath);
        report.put("summary", findings.summary());
        report.put("graph", graph);
        if (runtime == null) {
            report.put("violations", findings.all());
        } else {
            // Same findings, each with the observed call count of its code (absent = not observed).
            ObjectMapper mapper = new ObjectMapper();
            List<Map<String, Object>> violations = new java.util.ArrayList<>();
            for (CloudFindings.Finding f : findings.all()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> row = mapper.convertValue(f, LinkedHashMap.class);
                Long calls = runtime.callsFor(f);
                if (calls != null) row.put("runtimeCalls", calls);
                violations.add(row);
            }
            report.put("violations", violations);
            report.put("runtime", runtime.summary(findings.all(), 20));
        }
        report.put("suggestions", suggestions);
        return report;
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
        log.info("  --dependency-check-report <path.json>  Import OWASP Dependency-Check JSON to rank real CVEs by blast radius (requires --security)");
        log.info("  --dot <path.dot>    Export visual graph to DOT format");
        log.info("  --analyze <nodeId>  Perform impact analysis for a node");
        log.info("  --snapshot <dir>    Save graph snapshot to directory");
        log.info("  --compare <file>    Compare with previous snapshot");
        log.info("  --cloud-readiness   Run AEM Cloud Service readiness check");
        log.info("  --bpa-report <path> Generate BPA-compatible report (.json or .html)");
        log.info("  --sarif <path.sarif.json> Export forbidden-API findings as SARIF 2.1.0");
        log.info("  --suggestions <path.json> Export refactoring suggestions as JSON");
        log.info("  --json <path.json>  Export findings (forbidden-API + cloud-readiness), graph stats and suggestions as JSON");
        log.info("  --fail-on-critical  Exit with code 1 if any CRITICAL finding exists (CI gate)");
        log.info("  --fail-on <level>   Exit with code 1 on any finding at <level> or above: CRITICAL, HIGH, MEDIUM, LOW, INFO");
        log.info("  --runtime-traces <otlp.json>  Join OpenTelemetry traces (Collector file exporter) to the graph; ranks findings by runtime calls");
        log.info("  --emit-otel-config <path>     Write an OpenTelemetry Java agent config that instruments the classes that matter");
        log.info("  --extended-checks   Also run content-package (OakPal; needs 'mvn package' first), legacy-path and service-user checks");
        log.info("Skipped by default: .git, node_modules, it.tests, ui.tests, Maven target/ and src/test/");
        log.info("  --watch             Start watching for file changes and update incrementally");
        log.info("");
        log.info("Web UI:      http://localhost:8080/");
        log.info("GraphQL:     http://localhost:8080/graphql");
        log.info("GraphiQL:    http://localhost:8080/graphiql");
        log.info("Swagger UI:  http://localhost:8080/swagger-ui.html");
    }
}
