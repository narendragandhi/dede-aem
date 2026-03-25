package com.dede;

import com.dede.discovery.ProjectScanner;
import com.dede.discovery.SourceParser;
import com.dede.discovery.OsgiLinker;
import com.dede.domain.GraphService;
import com.dede.knowledge.GovernanceEngine;
import com.dede.intelligence.VulnerabilityService;
import com.dede.intelligence.GraphAgentSkills;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.io.File;
import java.util.List;

@SpringBootApplication
public class DedeApplication {

    private static final Logger log = LoggerFactory.getLogger(DedeApplication.class);
    private static final String VERSION = "1.1.0";

    public static void main(String[] args) {
        SpringApplication.run(DedeApplication.class, args);
    }

    @Bean
    public CommandLineRunner commandLineRunner(ProjectScanner scanner, SourceParser sourceParser,
                                             GraphService graphService, GovernanceEngine governance,
                                             VulnerabilityService security, GraphAgentSkills agent,
                                             OsgiLinker osgiLinker) {
        return args -> {
            if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
                printHelp();
                return;
            }

            String projectPath = args[0];
            String rulesPath = null;
            String dotOutputPath = null;
            String analyzeNodeId = null;
            boolean checkSecurity = false;
            String profiles = "aem"; // Default

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

            // AI Insights
            List<String> suggestions = agent.suggestRefactoring();
            if (suggestions.isEmpty()) {
                log.info("AI Refactoring Suggestions: Architecture looks solid!");
            } else {
                log.info("AI Refactoring Suggestions:");
                suggestions.forEach(s -> log.info("  - {}", s));
            }
        };
    }

    private void printBanner() {
        log.info("=================================================");
        log.info("Dede-Java Architectural Intelligence Engine v{}", VERSION);
        log.info("Inspired by Mitko Kolev's 'dede'");
        log.info("=================================================");
    }

    private void printHelp() {
        log.info("Usage: dede <project-path> [options]");
        log.info("Options:");
        log.info("  --profiles <p1,p2>  Comma-separated profiles (default: aem)");
        log.info("  --rules <path>      Path to dede-rules.json");
        log.info("  --security          Enable security reachability audit");
        log.info("  --dot <path.dot>    Export visual graph to DOT format");
        log.info("  --analyze <nodeId>  Perform impact analysis for a node");
    }
}
