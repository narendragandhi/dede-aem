package com.dede.cli;

import com.dede.analysis.ProjectScanner;
import com.dede.core.GraphService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
public class DedeCli implements CommandLineRunner {

    private final ProjectScanner projectScanner;
    private final GraphService graphService;
    private final com.dede.core.cache.MetadataCache cache;
    private final com.dede.analysis.OsgiLinker osgiLinker;

    public DedeCli(ProjectScanner projectScanner, GraphService graphService, com.dede.core.cache.MetadataCache cache, com.dede.analysis.OsgiLinker osgiLinker) {
        this.projectScanner = projectScanner;
        this.graphService = graphService;
        this.cache = cache;
        this.osgiLinker = osgiLinker;
    }

    @Override
    public void run(String... args) throws Exception {
        if (args.length == 0) {
            System.out.println("Usage: java -jar dede.jar <path-to-scan>");
            return;
        }

        String path = args[0];
        projectScanner.scan(path);
        
        // Link OSGi bundles
        osgiLinker.link();
        
        // Save cache to disk
        cache.save();
        
        System.out.println("Scan complete.");
        System.out.println("Nodes: " + graphService.getNodeCount());
        System.out.println("Edges: " + graphService.getEdgeCount());

        // Debug: Print BUNDLE nodes
        System.out.println("\n--- OSGi Bundles Found ---");
        graphService.getAllNodes().stream()
                .filter(node -> node.getType() == com.dede.core.model.NodeType.BUNDLE)
                .forEach(node -> System.out.println("Bundle: " + node.getName()));
    }
}
