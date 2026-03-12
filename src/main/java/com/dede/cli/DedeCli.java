package com.dede.cli;

import com.dede.analysis.ProjectScanner;
import com.dede.core.GraphService;
import com.dede.governance.GovernanceEngine;
import com.dede.agent.GraphAgentSkills;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DedeCli implements CommandLineRunner {

    private final ProjectScanner projectScanner;
    private final GraphService graphService;
    private final com.dede.core.cache.MetadataCache cache;
    private final com.dede.analysis.OsgiLinker osgiLinker;
    private final GovernanceEngine governanceEngine;
    private final GraphAgentSkills graphAgentSkills;

    public DedeCli(ProjectScanner projectScanner, GraphService graphService, com.dede.core.cache.MetadataCache cache, 
                   com.dede.analysis.OsgiLinker osgiLinker, GovernanceEngine governanceEngine, GraphAgentSkills graphAgentSkills) {
        this.projectScanner = projectScanner;
        this.graphService = graphService;
        this.cache = cache;
        this.osgiLinker = osgiLinker;
        this.governanceEngine = governanceEngine;
        this.graphAgentSkills = graphAgentSkills;
    }

    @Override
    public void run(String... args) throws Exception {
        if (args.length == 0) {
            System.out.println("Usage: java -jar dede.jar <path-to-scan> [--rules rules.json]");
            return;
        }

        String path = args[0];
        String rulesPath = null;
        for (int i = 0; i < args.length; i++) {
            if ("--rules".equals(args[i]) && i + 1 < args.length) {
                rulesPath = args[i + 1];
            }
        }

        projectScanner.scan(path);
        osgiLinker.link();
        cache.save();
        
        System.out.println("Scan complete.");
        System.out.println("Nodes: " + graphService.getNodeCount());
        System.out.println("Edges: " + graphService.getEdgeCount());

        if (rulesPath != null) {
            System.out.println("\n--- Applying Architectural Guardrails ---");
            List<String> violations = governanceEngine.validate(rulesPath);
            if (violations.isEmpty()) {
                System.out.println("✅ All architectural guardrails passed.");
            } else {
                System.err.println("❌ Architectural Guardrail Violations found:");
                violations.forEach(System.err::println);
            }
        }

        System.out.println("\n--- 🤖 AI Refactoring Suggestions ---");
        List<String> suggestions = graphAgentSkills.suggestRefactoring();
        if (suggestions.isEmpty()) {
            System.out.println("No refactoring suggestions. Architecture looks solid!");
        } else {
            suggestions.forEach(System.out::println);
        }

        System.out.println("\n--- OSGi Bundles Found ---");
        graphService.getAllNodes().stream()
                .filter(node -> node.getType() == com.dede.core.model.NodeType.BUNDLE)
                .forEach(node -> System.out.println("Bundle: " + node.getName()));
    }
}
