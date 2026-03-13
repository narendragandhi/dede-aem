package com.dede;

import com.dede.discovery.ProjectScanner;
import com.dede.discovery.SourceParser;
import com.dede.domain.GraphService;
import com.dede.knowledge.GovernanceEngine;
import com.dede.intelligence.VulnerabilityService;
import com.dede.intelligence.GraphAgentSkills;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.io.File;
import java.util.List;

@SpringBootApplication
public class DedeApplication {

    public static void main(String[] args) {
        SpringApplication.run(DedeApplication.class, args);
    }

    @Bean
    public CommandLineRunner commandLineRunner(ProjectScanner scanner, SourceParser sourceParser, 
                                             GraphService graphService, GovernanceEngine governance,
                                             VulnerabilityService security, GraphAgentSkills agent) {
        return args -> {
            if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
                printHelp();
                return;
            }

            String projectPath = args[0];
            String rulesPath = null;
            String dotOutputPath = null;
            boolean checkSecurity = false;
            String profiles = "aem"; // Default

            for (int i = 0; i < args.length; i++) {
                if ("--rules".equals(args[i]) && i + 1 < args.length) {
                    rulesPath = args[i + 1];
                }
                if ("--dot".equals(args[i]) && i + 1 < args.length) {
                    dotOutputPath = args[i + 1];
                }
                if ("--security".equals(args[i])) {
                    checkSecurity = true;
                }
                if ("--profiles".equals(args[i]) && i + 1 < args.length) {
                    profiles = args[i + 1];
                }
            }

            printBanner();

            sourceParser.loadProfiles(profiles.split(","));
            scanner.scan(projectPath);

            if (rulesPath != null) {
                governance.loadRules(new File(rulesPath));
                governance.validate(graphService.getGraph());
            }

            if (checkSecurity) {
                security.audit(graphService.getGraph());
            }

            if (dotOutputPath != null) {
                System.out.println("📝 Exporting graph to DOT: " + dotOutputPath);
                graphService.exportToDot(new File(dotOutputPath));
            }

            System.out.println("\n📊 Scan Summary:");
            System.out.println("   - Total Nodes: " + graphService.getNodeCount());
            System.out.println("   - Total Edges: " + graphService.getEdgeCount());

            if (rulesPath != null) {
                governance.printViolations();
            }

            if (checkSecurity) {
                security.printReport();
            }

            // AI Insights
            System.out.println("\n🤖 AI Refactoring Suggestions:");
            List<String> suggestions = agent.suggestRefactoring();
            if (suggestions.isEmpty()) {
                System.out.println("   Architecture looks solid!");
            } else {
                suggestions.forEach(s -> System.out.println("   💡 " + s));
            }
        };
    }

    private void printBanner() {
        System.out.println("🚀 Dede-Java Architectural Intelligence Engine v1.1.0");
        System.out.println("Inspired by Mitko Kolev's 'dede' (https://github.com/mitkox/dede)");
        System.out.println("--------------------------------------------------");
    }

    private void printHelp() {
        System.out.println("Usage: dede <project-path> [options]");
        System.out.println("Options:");
        System.out.println("  --profiles <p1,p2>  Comma-separated profiles (default: aem)");
        System.out.println("  --rules <path>      Path to dede-rules.json");
        System.out.println("  --security          Enable security reachability audit");
        System.out.println("  --dot <path.dot>    Export visual graph to DOT format");
    }
}
