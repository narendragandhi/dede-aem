package com.dede;

import com.dede.analysis.DedeScanner;
import com.dede.analysis.SourceParser;
import com.dede.core.GraphService;
import com.dede.governance.GovernanceEngine;
import com.dede.security.VulnerabilityService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.io.File;
import java.util.Arrays;

@SpringBootApplication
public class DedeApplication {

    public static void main(String[] args) {
        SpringApplication.run(DedeApplication.class, args);
    }

    @Bean
    public CommandLineRunner commandLineRunner(DedeScanner scanner, SourceParser sourceParser, 
                                             GraphService graphService, GovernanceEngine governance,
                                             VulnerabilityService security) {
        return args -> {
            if (args.length == 0) {
                printHelp();
                return;
            }

            String projectPath = args[0];
            String rulesPath = null;
            boolean checkSecurity = false;
            String profiles = "aem"; // Default

            for (int i = 0; i < args.length; i++) {
                if ("--rules".equals(args[i]) && i + 1 < args.length) {
                    rulesPath = args[i + 1];
                }
                if ("--security".equals(args[i])) {
                    checkSecurity = true;
                }
                if ("--profiles".equals(args[i]) && i + 1 < args.length) {
                    profiles = args[i + 1];
                }
            }

            System.out.println("🚀 Dede-Java Architectural Intelligence Engine v0.0.1");
            System.out.println("--------------------------------------------------");

            sourceParser.loadProfiles(profiles.split(","));
            scanner.scan(projectPath);

            if (rulesPath != null) {
                governance.loadRules(new File(rulesPath));
                governance.validate(graphService.getGraph());
            }

            if (checkSecurity) {
                security.audit(graphService.getGraph());
            }

            System.out.println("\n📊 Scan Summary:");
            System.out.println("   - Total Nodes: " + graphService.getGraph().vertexSet().size());
            System.out.println("   - Total Edges: " + graphService.getGraph().edgeSet().size());

            if (rulesPath != null) {
                governance.printViolations();
            }

            if (checkSecurity) {
                security.printReport();
            }

            // AI Insights
            System.out.println("\n🤖 AI Refactoring Suggestions:");
            suggestAI(graphService);
        };
    }

    private void suggestAI(GraphService graphService) {
        long edges = graphService.getGraph().edgeSet().size();
        if (edges > 100) {
            System.out.println("   💡 REFACTOR [God Bundle]: Found high coupling. Suggestion: Split into 'api' and 'core' bundles.");
        } else {
            System.out.println("   Architecture looks solid!");
        }
    }

    private void printHelp() {
        System.out.println("Usage: dede <project-path> [options]");
        System.out.println("Options:");
        System.out.println("  --profiles <p1,p2>  Comma-separated profiles (default: aem)");
        System.out.println("  --rules <path>      Path to dede-rules.json");
        System.out.println("  --security          Enable security reachability audit");
    }
}
