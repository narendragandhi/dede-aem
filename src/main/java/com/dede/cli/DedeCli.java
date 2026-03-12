package com.dede.cli;

import com.dede.analysis.ProjectScanner;
import com.dede.core.GraphService;
import com.dede.governance.GovernanceEngine;
import com.dede.agent.GraphAgentSkills;
import com.dede.security.VulnerabilityService;
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
    private final VulnerabilityService vulnerabilityService;

    public DedeCli(ProjectScanner projectScanner, GraphService graphService, com.dede.core.cache.MetadataCache cache, 
                   com.dede.analysis.OsgiLinker osgiLinker, GovernanceEngine governanceEngine, 
                   GraphAgentSkills graphAgentSkills, VulnerabilityService vulnerabilityService) {
        this.projectScanner = projectScanner;
        this.graphService = graphService;
        this.cache = cache;
        this.osgiLinker = osgiLinker;
        this.governanceEngine = governanceEngine;
        this.graphAgentSkills = graphAgentSkills;
        this.vulnerabilityService = vulnerabilityService;
    }

    @Override
    public void run(String... args) throws Exception {
        if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
            printHelp();
            return;
        }

        String path = args[0];
        String rulesPath = null;
        boolean runSecurity = false;

        for (int i = 0; i < args.length; i++) {
            if ("--rules".equals(args[i]) && i + 1 < args.length) {
                rulesPath = args[i + 1];
            } else if ("--security".equals(args[i])) {
                runSecurity = true;
            }
        }

        System.out.println("🚀 Dede-Java Architectural Intelligence Engine v0.0.1");
        System.out.println("--------------------------------------------------");
        
        projectScanner.scan(path);
        osgiLinker.link();
        cache.save();
        
        System.out.println("\n📊 Scan Summary:");
        System.out.println("   - Total Nodes: " + graphService.getNodeCount());
        System.out.println("   - Total Edges: " + graphService.getEdgeCount());

        if (rulesPath != null) {
            System.out.println("\n⚖️  Architectural Guardrails:");
            List<String> violations = governanceEngine.validate(rulesPath);
            if (violations.isEmpty()) {
                System.out.println("   ✅ All guardrails passed.");
            } else {
                violations.forEach(v -> System.err.println("   ❌ " + v));
            }
        }

        if (runSecurity) {
            System.out.println("\n🛡️  Security Reachability Analysis:");
            List<String> findings = vulnerabilityService.runSecurityAudit();
            if (findings.isEmpty()) {
                System.out.println("   ✅ No reachable vulnerabilities detected.");
            } else {
                findings.forEach(f -> System.out.println("   ⚠️ " + f));
            }
        }

        System.out.println("\n🤖 AI Refactoring Suggestions:");
        List<String> suggestions = graphAgentSkills.suggestRefactoring();
        if (suggestions.isEmpty()) {
            System.out.println("   Architecture looks solid!");
        } else {
            suggestions.forEach(s -> System.out.println("   💡 " + s));
        }
    }

    private void printHelp() {
        System.out.println("Usage: java -jar dede.jar <path-to-project> [options]");
        System.out.println("\nOptions:");
        System.out.println("  <path-to-project>    Root directory of the AEM/Java project to scan.");
        System.out.println("  --rules <file.json>  Path to a JSON file containing architectural guardrail rules.");
        System.out.println("  --security           Perform security reachability analysis for known CVEs.");
        System.out.println("  --help, -h           Show this help message.");
        System.out.println("\nExample:");
        System.out.println("  java -jar dede.jar ./my-aem-project --rules my-rules.json --security");
    }
}
