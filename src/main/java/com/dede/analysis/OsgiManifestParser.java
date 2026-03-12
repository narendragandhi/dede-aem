package com.dede.analysis;

import com.dede.core.GraphService;
import com.dede.core.model.CodeNode;
import com.dede.core.model.NodeType;
import com.dede.core.model.Relationship;
import com.dede.core.model.RelationshipType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

@Component
public class OsgiManifestParser {

    private final GraphService graphService;

    public OsgiManifestParser(GraphService graphService) {
        this.graphService = graphService;
    }

    public void parse(Path manifestPath) {
        try (InputStream is = Files.newInputStream(manifestPath)) {
            parseFromManifestObject(new Manifest(is), manifestPath.toString());
        } catch (IOException e) {
            System.err.println("Error parsing manifest: " + manifestPath);
        }
    }

    public com.dede.core.cache.BundleMetadata parseFromManifestObject(Manifest manifest, String location) {
        Attributes mainAttributes = manifest.getMainAttributes();

        String bundleSymbolicName = mainAttributes.getValue("Bundle-SymbolicName");
        if (bundleSymbolicName == null) return null;

        bundleSymbolicName = bundleSymbolicName.split(";")[0].trim();
        String bundleVersion = mainAttributes.getValue("Bundle-Version");
        if (bundleVersion == null) bundleVersion = "0.0.0";

        String fragmentHost = mainAttributes.getValue("Fragment-Host");
        if (fragmentHost != null) fragmentHost = fragmentHost.split(";")[0].trim();

        java.util.Map<String, String> exportMap = new java.util.HashMap<>();
        String exports = mainAttributes.getValue("Export-Package");
        if (exports != null) {
            exportMap = parsePackagesWithVersion(exports, "version");
        }

        java.util.Map<String, String> importMap = new java.util.HashMap<>();
        String imports = mainAttributes.getValue("Import-Package");
        if (imports != null) {
            importMap = parsePackagesWithVersion(imports, "version");
        }

        com.dede.core.cache.BundleMetadata metadata = new com.dede.core.cache.BundleMetadata(
                bundleSymbolicName, bundleVersion, exportMap, importMap, 
                new java.util.ArrayList<>(), new java.util.ArrayList<>(),
                new java.io.File(location).lastModified(), 
                new java.io.File(location).length()
        );

        CodeNode bundleNode = buildGraphFromMetadata(metadata, location);
        if (fragmentHost != null) {
            CodeNode hostNode = new CodeNode("bundle:" + fragmentHost, fragmentHost, NodeType.BUNDLE, fragmentHost, null);
            graphService.addNode(hostNode);
            graphService.addEdge(bundleNode, hostNode, RelationshipType.FRAGMENTS_TO);
        }
        return metadata;
    }

    public CodeNode buildGraphFromMetadata(com.dede.core.cache.BundleMetadata metadata, String location) {
        CodeNode bundleNode = new CodeNode("bundle:" + metadata.symbolicName(), metadata.symbolicName(), 
                NodeType.BUNDLE, metadata.symbolicName() + ":" + metadata.bundleVersion(), location);
        bundleNode.getProperties().put("version", metadata.bundleVersion());
        graphService.addNode(bundleNode);

        metadata.exports().keySet().forEach(pkg -> {
            String ver = metadata.exports().get(pkg);
            CodeNode pkgNode = new CodeNode("pkg:" + pkg, pkg, NodeType.PACKAGE, pkg, null);
            graphService.addNode(pkgNode);
            Relationship edge = graphService.addEdge(bundleNode, pkgNode, RelationshipType.EXPORTS);
            edge.getProperties().put("version", ver);

            // Export Awareness: Mark all classes in this package as exported
            graphService.getRelatedNodes(pkgNode, RelationshipType.CONTAINS).forEach(node -> {
                node.getProperties().put("isExported", "true");
            });
        });

        metadata.imports().keySet().forEach(pkg -> {
            String range = metadata.imports().get(pkg);
            CodeNode pkgNode = new CodeNode("pkg:" + pkg, pkg, NodeType.PACKAGE, pkg, null);
            graphService.addNode(pkgNode);
            Relationship edge = graphService.addEdge(bundleNode, pkgNode, RelationshipType.IMPORTS);
            edge.getProperties().put("versionRange", range);
        });

        metadata.providedServices().forEach(svc -> {
            CodeNode svcNode = new CodeNode("svc:" + svc, svc, NodeType.OSGI_SERVICE, svc, null);
            graphService.addNode(svcNode);
            graphService.addEdge(bundleNode, svcNode, RelationshipType.PROVIDES);
        });

        metadata.consumedServices().forEach(svc -> {
            CodeNode svcNode = new CodeNode("svc:" + svc, svc, NodeType.OSGI_SERVICE, svc, null);
            graphService.addNode(svcNode);
            graphService.addEdge(bundleNode, svcNode, RelationshipType.CONSUMES);
        });
        return bundleNode;
    }

    private java.util.Map<String, String> parsePackagesWithVersion(String headerValue, String attrName) {
        java.util.Map<String, String> result = new java.util.HashMap<>();
        String[] parts = headerValue.split(",(?=[^\\\"]*(?:\\\"[^\\\"]*\\\"[^\\\"]*)*$)");
        for (String part : parts) {
            String[] segments = part.split(";");
            String pkgName = segments[0].trim();
            String version = "0.0.0";
            for (int i = 1; i < segments.length; i++) {
                String segment = segments[i].trim();
                if (segment.startsWith(attrName + "=")) {
                    version = segment.substring(attrName.length() + 1).replace("\"", "").trim();
                }
            }
            result.put(pkgName, version);
        }
        return result;
    }
}
