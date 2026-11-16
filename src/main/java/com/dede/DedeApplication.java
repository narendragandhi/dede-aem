package com.dede;

import com.dede.cli.CliOptions;
import com.dede.cli.RuntimeJoin;
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
import com.dede.runtime.RuntimeOverlay;
import com.dede.mcp.DedeTools;
import com.dede.mcp.McpServer;
import com.dede.runtime.BlastRadiusExport;
import com.dede.runtime.ErrorAttribution;
import com.dede.runtime.RequestAttribution;
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

@SpringBootApplication
public class DedeApplication {

    private static final Logger log = LoggerFactory.getLogger(DedeApplication.class);
    private static final String VERSION = "2.0.0";

    /** stdout as it was at startup: the MCP protocol channel in --mcp mode (logs go to stderr). */
    private static java.io.PrintStream protocolOut = System.out;

    public static void main(String[] args) {
        if (java.util.Arrays.asList(args).contains("--mcp")) {
            // MCP over stdio: stdout carries JSON-RPC only. Everything else -- Spring's banner,
            // the scan's log output -- goes to stderr, which MCP clients show as server logs.
            protocolOut = System.out;
            System.setOut(System.err);
        }
        SpringApplication app = new SpringApplication(DedeApplication.class);
        if (isOneShotScan(args)) {
            // A CLI scan runs and exits: don't start the REST/GraphQL server (and its API-key
            // banner). Server mode (no args) and --watch keep the web layer.
            app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        }
        app.run(args);
    }

    static boolean isOneShotScan(String[] args) {
        return args.length > 0 && !args[0].startsWith("-") && !java.util.Arrays.asList(args).contains("--watch");
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

            CliOptions o;
            try {
                o = CliOptions.parse(args);
            } catch (CliOptions.UsageException e) {
                log.error("{}. Run with --help for the supported options.", e.getMessage());
                System.exit(SpringApplication.exit(context, () -> 2));
                return;
            }
            o.unknownFlags().forEach(flag ->
                log.warn("Unknown option '{}' ignored. Run with --help for the supported options.", flag));

            try {
                int exitCode = runScan(o, scanner, sourceParser, graphService, governance, security, agent, osgiLinker,
                    deltaAnalyzer, cloudAnalyzer, bpaGenerator, cloudFindings, cveImporter);
                if (o.watch()) {
                    scanner.startWatching(o.projectPath());
                } else {
                    System.exit(SpringApplication.exit(context, () -> exitCode));
                }
            } catch (RuntimeJoin.StageFailure e) {
                // A CI step relying on runtime data or a report must not silently get a result without it.
                log.error(e.getMessage());
                System.exit(SpringApplication.exit(context, () -> 2));
            }
        };
    }


    /**
     * One scan, in stage order: static scan and analyses, findings, the runtime join, then
     * everything that reads runtime data (snapshots, blast radius, reports, MCP).
     *
     * @return the exit code: 1 when the --fail-on gate fails, else 0
     */
    private int runScan(CliOptions o, ProjectScanner scanner, SourceParser sourceParser, GraphService graphService,
                        GovernanceEngine governance, VulnerabilityService security, GraphAgentSkills agent,
                        OsgiLinker osgiLinker, DeltaAnalyzer deltaAnalyzer, CloudReadinessAnalyzer cloudAnalyzer,
                        BpaReportGenerator bpaGenerator, CloudFindingsService cloudFindings,
                        DependencyCveImporter cveImporter) throws Exception {
        // 1. Static scan: the graph.
        printBanner();
        log.info("Starting scan of project: {}", o.projectPath());
        log.info("Using profiles: {}", o.profiles());
        sourceParser.loadProfiles(o.profiles().split(","));
        scanner.scan(o.projectPath());
        log.info("Running OSGi linker to connect bundles and configurations...");
        osgiLinker.link();
        if (o.dependencyCheckReportPath() != null) {
            log.info("Importing CVE findings from dependency-check report: {}", o.dependencyCheckReportPath());
            cveImporter.importReport(Path.of(o.dependencyCheckReportPath()));
        }

        // 2. Static analyses over the graph.
        if (o.analyzeNodeId() != null) {
            log.info("Impact Analysis for '{}': {}", o.analyzeNodeId(), agent.analyzeImpact(o.analyzeNodeId()));
        }
        if (o.rulesPath() != null) {
            governance.loadRules(new File(o.rulesPath()));
            governance.validate(graphService.getGraph());
        }
        if (o.checkSecurity()) {
            security.audit(graphService.getGraph());
        }
        if (o.dotOutputPath() != null) {
            log.info("Exporting graph to DOT: {}", o.dotOutputPath());
            graphService.exportToDot(new File(o.dotOutputPath()));
        }
        log.info("Scan Summary: {} nodes, {} edges", graphService.getNodeCount(), graphService.getEdgeCount());
        if (o.rulesPath() != null) {
            governance.printViolations();
        }
        if (o.cloudReadiness()) {
            log.info("Running AEM Cloud Service readiness analysis...");
            log.info("\n{}", cloudAnalyzer.generateTextReport(cloudAnalyzer.analyze()));
        }
        if (o.bpaReportPath() != null) {
            writeBpaReport(o, cloudAnalyzer, bpaGenerator);
        }

        // 3. One set of findings (catalog scan + graph checks), shared by SARIF, the JSON report,
        //    the gate and the runtime joins -- the same set the Maven plugin gates on.
        Path scanRoot = Path.of(o.projectPath());
        CloudFindings findings = o.needsFindings() ? cloudFindings.collect(scanRoot, o.extendedChecks()) : null;

        // 4. Runtime join: writes runtime.* onto the graph. Everything below reads it.
        RuntimeJoin.RuntimeData runtime = RuntimeJoin.run(o, findings, graphService);

        // 5. Consumers of the graph with runtime data.
        if (o.snapshotPath() != null) {
            log.info("Snapshot saved: {}", deltaAnalyzer.saveSnapshot(Path.of(o.snapshotPath())));
        }
        if (o.compareSnapshot() != null) {
            log.info("Comparing with snapshot: {}", o.compareSnapshot());
            log.info("\n{}", deltaAnalyzer.generateTextReport(deltaAnalyzer.compareWithSnapshot(Path.of(o.compareSnapshot()))));
        }
        if (o.checkSecurity()) {
            if (runtime.hasTraffic() && security.weightByRuntime(graphService.getGraph())) {
                log.info("Blast radius: ranked by traffic observed at the reachable endpoints, then endpoint count.");
            }
            security.printReport();
        }
        if (o.otelConfigPath() != null) {
            writeOtelConfig(o, graphService, findings);
        }
        for (String name : o.blastRadius()) {
            writeBlastRadius(o, name, graphService, runtime, findings);
        }
        if (o.sarifOutputPath() != null) {
            writeJson(o.sarifOutputPath(), new SarifReport(findings.forbiddenApis().violations(), scanRoot).toSarif(),
                "SARIF report", false);
        }
        List<String> suggestions = agent.suggestRefactoring();
        if (suggestions.isEmpty()) {
            log.info("AI Refactoring Suggestions: Architecture looks solid!");
        } else {
            log.info("AI Refactoring Suggestions:");
            suggestions.forEach(s -> log.info("  - {}", s));
        }
        if (o.suggestionsOutputPath() != null) {
            writeJson(o.suggestionsOutputPath(), suggestions, "refactoring suggestions", false);
        }
        Map<String, Object> report = o.jsonOutputPath() != null || o.mcp()
            ? buildJsonReport(o.projectPath(), graphService, findings, suggestions, runtime.traces(),
                runtime.instrumentedNotCalled(), runtime.requests(), runtime.errors(),
                o.checkSecurity() ? security.getBlastRadiusFindings() : null)
            : null;
        if (o.jsonOutputPath() != null) {
            // A CI gate reading this file must not silently see "no findings".
            writeJson(o.jsonOutputPath(), report, "JSON report", true);
        }

        // 6. Gate, then serve or exit.
        int exitCode = 0;
        String gateFailure = o.failOn() != null ? findings.failureMessage(o.failOn()) : null;
        if (gateFailure != null) {
            log.error("Failing (--fail-on {}): {}", o.failOn(), gateFailure);
            exitCode = 1;
        }
        if (o.mcp()) {
            log.info("MCP server ready on stdio ({} findings, {} nodes). Tools: summary, list_findings, "
                + "class_info, impact, search.", findings.all().size(), graphService.getNodeCount());
            new McpServer("dede", VERSION, new DedeTools(graphService, report).tools()).serve(System.in, protocolOut);
            return 0;
        }
        return exitCode;
    }

    private static void writeBpaReport(CliOptions o, CloudReadinessAnalyzer cloudAnalyzer,
                                       BpaReportGenerator bpaGenerator) throws IOException {
        log.info("Generating BPA-compatible report: {}", o.bpaReportPath());
        String projectName = Path.of(o.projectPath()).getFileName().toString();
        var bpaReport = bpaGenerator.generateReport(cloudAnalyzer.analyze(), projectName);
        Path outputPath = Path.of(o.bpaReportPath());
        if (o.bpaReportPath().endsWith(".html")) {
            bpaGenerator.exportToHtml(bpaReport, outputPath);
        } else {
            bpaGenerator.exportToJson(bpaReport, outputPath);
        }
        log.info("BPA report saved to: {}", o.bpaReportPath());
    }

    private static void writeOtelConfig(CliOptions o, GraphService graphService, CloudFindings findings)
            throws RuntimeJoin.StageFailure {
        int maxClasses = o.otelMaxClasses();
        var selections = OtelConfigEmitter.select(graphService, findings.all(), maxClasses);
        if (selections.size() == maxClasses) {
            log.warn("OpenTelemetry config: capped at {} classes to keep agent overhead down; classes with "
                + "findings were picked first, then entry points, then by fan-in.", maxClasses);
        }
        try {
            java.nio.file.Files.writeString(Path.of(o.otelConfigPath()), OtelConfigEmitter.render(selections));
            log.info("OpenTelemetry agent config for {} class(es) written to {}", selections.size(), o.otelConfigPath());
        } catch (IOException e) {
            throw new RuntimeJoin.StageFailure("Failed to write OpenTelemetry config to " + o.otelConfigPath() + ": " + e.getMessage());
        }
    }

    private static void writeBlastRadius(CliOptions o, String name, GraphService graphService,
                                         RuntimeJoin.RuntimeData runtime, CloudFindings findings)
            throws RuntimeJoin.StageFailure {
        Map<String, Object> export;
        try {
            export = BlastRadiusExport.build(name, graphService, runtime.content(), runtime.requests(), findings.all(),
                Path.of(o.projectPath()));
        } catch (IllegalArgumentException e) {
            throw new RuntimeJoin.StageFailure("--blast-radius " + name + ": " + e.getMessage());
        }
        @SuppressWarnings("unchecked") Map<String, Object> summary = (Map<String, Object>) export.get("summary");
        log.info("Blast radius of {}: {} dependent(s) over {} hop(s), {} resource type(s), {} page(s), {} observed request(s), {} finding(s)",
            name, summary.get("dependents"), summary.get("maxHop"), summary.get("resourceTypes"), summary.get("pages"),
            summary.get("observedRequests") == null ? "no" : summary.get("observedRequests"), summary.get("findings"));
        String fileName = ((Map<?, ?>) export.get("root")).get("name").toString().replaceAll("[^A-Za-z0-9._-]", "_");
        try {
            java.nio.file.Files.createDirectories(Path.of(o.blastRadiusDir()));
        } catch (IOException e) {
            throw new RuntimeJoin.StageFailure("Cannot create " + o.blastRadiusDir() + ": " + e.getMessage());
        }
        writeJson(Path.of(o.blastRadiusDir(), "blast-radius-" + fileName + ".json").toString(), export, "blast radius", true);
    }

    /** Writes {@code value} as indented JSON; a {@code required} output that fails stops the scan. */
    private static void writeJson(String path, Object value, String what, boolean required) throws RuntimeJoin.StageFailure {
        log.info("Writing {}: {}", what, path);
        try {
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(new File(path), value);
        } catch (IOException e) {
            String message = "Failed to write " + what + " to " + path + ": " + e.getMessage();
            if (required) throw new RuntimeJoin.StageFailure(message);
            log.error(message);
        }
    }

    private static Map<String, Object> buildJsonReport(String projectPath, GraphService graphService,
                                                       CloudFindings findings, List<String> suggestions,
                                                       RuntimeOverlay runtime,
                                                       List<Map<String, Object>> instrumentedNotCalled,
                                                       RequestAttribution requestAttribution,
                                                       ErrorAttribution errorAttribution,
                                                       List<VulnerabilityService.BlastRadiusFinding> blastRadius) {
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("nodes", graphService.getNodeCount());
        graph.put("edges", graphService.getEdgeCount());

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("tool", "dede");
        report.put("version", VERSION);
        report.put("project", projectPath);
        report.put("summary", findings.summary());
        report.put("graph", graph);
        if (runtime == null && errorAttribution == null) {
            report.put("violations", findings.all());
        } else {
            // Same findings, each with the observed call count of its code (absent = not observed).
            ObjectMapper mapper = new ObjectMapper();
            List<Map<String, Object>> violations = new java.util.ArrayList<>();
            for (CloudFindings.Finding f : findings.all()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> row = mapper.convertValue(f, LinkedHashMap.class);
                Long calls = runtime != null ? runtime.callsFor(f) : null;
                if (calls != null) row.put("runtimeCalls", calls);
                Long methodCalls = runtime != null ? runtime.methodCallsFor(f) : null;
                if (methodCalls != null) {
                    row.put("runtimeMethod", runtime.enclosingMethod(f));
                    row.put("runtimeMethodCalls", methodCalls);
                }
                Long logEntries = errorAttribution != null ? errorAttribution.entriesFor(f) : null;
                if (logEntries != null) row.put("logEntries", logEntries);
                violations.add(row);
            }
            report.put("violations", violations);
        }
        if (runtime != null) {
            Map<String, Object> runtimeBlock = runtime.summary(findings.all(), 20);
            if (instrumentedNotCalled != null) {
                runtimeBlock.put("windowHours", Math.round(runtime.windowHours() * 100) / 100.0);
                runtimeBlock.put("instrumentedNotCalled", instrumentedNotCalled);
            }
            report.put("runtime", runtimeBlock);
        }
        if (requestAttribution != null) {
            report.put("requests", requestAttribution.summary(20));
        }
        if (errorAttribution != null) {
            report.put("logs", errorAttribution.summary(findings.all(), 20));
        }
        if (blastRadius != null) {
            report.put("vulnerabilities", blastRadius.stream().map(b -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("cveId", b.cveId());
                row.put("severity", b.severity());
                row.put("cvssScore", b.cvssScore());
                row.put("dependency", b.vulnerability().getProperties().get("dependency"));
                row.put("blastRadius", b.blastRadius());
                if (b.observedTraffic() != null) row.put("observedTraffic", b.observedTraffic());
                row.put("endpoints", b.reachableEndpointIds());
                return row;
            }).toList());
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
        log.info("  --request-log <path>          AEM request.log or access.log: attribute requests to servlets, pages, components and models");
        log.info("  --content <zip|json|url>      With --request-log: the site's authored pages (content package, saved QueryBuilder JSON, or live AEM URL)");
        log.info("  --blast-radius <class>        Export what depends on <class> -- code, components, pages, traffic -- as JSON (repeatable)");
        log.info("  --blast-radius-dir <dir>      Where --blast-radius writes blast-radius-<name>.json (default: current directory)");
        log.info("  --error-log <path>            AEM error.log: tie WARN/ERROR entries to project classes and their findings");
        log.info("  --mcp                         After the scan, serve it to AI agents as an MCP server on stdio (logs go to stderr)");
        log.info("  --otel-max-classes <n>        Class cap for --emit-otel-config (default 50)");
        log.info("  --otel-config <path>          With --runtime-traces: the agent config used, to report instrumented-but-never-called classes");
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
