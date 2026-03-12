package com.dede.analysis;

import com.dede.core.GraphService;
import com.dede.core.model.CodeNode;
import com.dede.core.model.NodeType;
import com.dede.core.model.Relationship;
import com.dede.core.model.RelationshipType;
import com.dede.core.util.VersionUtil;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

@Component
public class OsgiLinker {

    private final GraphService graphService;

    public OsgiLinker(GraphService graphService) {
        this.graphService = graphService;
    }

    public void link() {
        Set<CodeNode> bundles = graphService.getAllNodes().stream()
                .filter(n -> n.getType() == NodeType.BUNDLE)
                .collect(Collectors.toSet());

        System.out.println("Linking " + bundles.size() + " bundles...");

        for (CodeNode importingBundle : bundles) {
            // 1. Version-Aware Package Linking
            Set<Relationship> importEdges = graphService.getEdgesOf(importingBundle, RelationshipType.IMPORTS, true);
            for (Relationship importEdge : importEdges) {
                CodeNode pkg = (CodeNode) importEdge.getTarget();
                String range = importEdge.getProperties().get("versionRange");
                
                Set<Relationship> exportEdges = graphService.getEdgesOf(pkg, RelationshipType.EXPORTS, false);
                for (Relationship exportEdge : exportEdges) {
                    CodeNode exportingBundle = (CodeNode) exportEdge.getSource();
                    String exportVer = exportEdge.getProperties().get("version");
                    
                    if (!importingBundle.equals(exportingBundle) && VersionUtil.matches(exportVer, range)) {
                        graphService.addEdge(importingBundle, exportingBundle, RelationshipType.WIRES_TO);
                    }
                }
            }

            // 2. Link via OSGi Services
            Set<CodeNode> consumedServices = graphService.getRelatedNodes(importingBundle, RelationshipType.CONSUMES);
            for (CodeNode svc : consumedServices) {
                Set<CodeNode> providingBundles = graphService.getInboundRelatedNodes(svc, RelationshipType.PROVIDES);
                for (CodeNode providingBundle : providingBundles) {
                    if (!importingBundle.equals(providingBundle)) {
                        graphService.addEdge(importingBundle, providingBundle, RelationshipType.WIRES_TO);
                    }
                }
            }
        }
    }
}
