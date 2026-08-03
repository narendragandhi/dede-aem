package com.dede.security;

import com.dede.domain.GraphService;
import com.dede.domain.model.CodeNode;
import com.dede.domain.model.NodeType;
import com.dede.domain.model.RelationshipType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Imports an OWASP Dependency-Check JSON report and creates a VULNERABILITY node
 * per (dependency, CVE) pair, linked via EXPOSES to a matching BUNDLE node when one
 * can be found by artifact name.
 *
 * Dede's own scanners (ForbiddenApiScanner, DispatcherParser, ...) already create
 * VULNERABILITY nodes for the tool's internal rule violations, and
 * VulnerabilityService already does real reachability analysis over the graph. This
 * is what feeds that same reachability engine real CVE data instead of only dede's
 * own findings, closing the gap between "OWASP Dependency-Check found a CVE" and
 * "this CVE is reachable from N public endpoints" -- the reachability-over-flat-list
 * pitch in docs/SECURITY_AUDIT.md, actually wired to real vulnerability data.
 */
@Component
public class DependencyCveImporter {

    private static final Logger log = LoggerFactory.getLogger(DependencyCveImporter.class);

    private final GraphService graphService;

    public DependencyCveImporter(GraphService graphService) {
        this.graphService = graphService;
    }

    public int importReport(Path reportPath) {
        int importedCount = 0;
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(reportPath.toFile());
            JsonNode dependencies = root.path("dependencies");
            if (!dependencies.isArray()) {
                log.warn("No 'dependencies' array found in {} -- not a dependency-check JSON report?", reportPath);
                return 0;
            }

            List<CodeNode> bundles = graphService.findNodesByType(NodeType.BUNDLE);
            int linked = 0;

            for (JsonNode dep : dependencies) {
                JsonNode vulns = dep.path("vulnerabilities");
                if (!vulns.isArray() || vulns.isEmpty()) {
                    continue;
                }

                String fileName = dep.path("fileName").asText("unknown-dependency");
                CodeNode bundleMatch = findMatchingBundle(fileName, bundles);

                for (JsonNode vuln : vulns) {
                    String cveId = vuln.path("name").asText(null);
                    if (cveId == null || cveId.isBlank()) {
                        continue;
                    }

                    String severity = vuln.path("severity").asText("UNKNOWN").toUpperCase(Locale.ROOT);
                    double cvss = extractCvssScore(vuln);

                    String nodeId = "cve:" + cveId + ":" + fileName;
                    CodeNode vulnNode = new CodeNode(nodeId, cveId, NodeType.VULNERABILITY,
                        severity + " (CVSS " + cvss + ") in " + fileName,
                        dep.path("filePath").asText(null));
                    vulnNode.getProperties().put("cveId", cveId);
                    vulnNode.getProperties().put("severity", severity);
                    vulnNode.getProperties().put("cvssScore", String.valueOf(cvss));
                    vulnNode.getProperties().put("dependency", fileName);

                    graphService.addNode(vulnNode);
                    importedCount++;

                    if (bundleMatch != null) {
                        graphService.addEdge(vulnNode, bundleMatch, RelationshipType.EXPOSES);
                        linked++;
                    } else {
                        log.debug("No bundle match for dependency {} ({}); {} recorded without a reachability link",
                            fileName, cveId, cveId);
                    }
                }
            }

            log.info("Imported {} CVE findings from {} ({} linked to a known bundle for reachability analysis)",
                importedCount, reportPath, linked);
        } catch (IOException e) {
            log.error("Failed to import dependency-check report {}: {}", reportPath, e.getMessage());
        }
        return importedCount;
    }

    private double extractCvssScore(JsonNode vuln) {
        if (vuln.path("cvssv3").has("baseScore")) {
            return vuln.path("cvssv3").path("baseScore").asDouble();
        }
        if (vuln.path("cvssv2").has("score")) {
            return vuln.path("cvssv2").path("score").asDouble();
        }
        return 0.0;
    }

    /**
     * Matches a dependency-check fileName ("commons-collections-3.2.1.jar") against
     * a bundle's symbolic name by stripping the version/extension to get a rough
     * artifact name and checking for containment. Deliberately approximate: OSGi
     * symbolic names rarely match Maven artifact IDs exactly (e.g.
     * "org.apache.commons.collections" vs "commons-collections"), so an exact match
     * would miss real hits far more often than a loose one produces false ones.
     */
    private CodeNode findMatchingBundle(String fileName, List<CodeNode> bundles) {
        String artifactGuess = fileName.replaceAll("-\\d.*$", "").replace(".jar", "").toLowerCase(Locale.ROOT);
        if (artifactGuess.isBlank()) {
            return null;
        }
        for (CodeNode bundle : bundles) {
            String symbolicName = bundle.getName().toLowerCase(Locale.ROOT);
            if (symbolicName.contains(artifactGuess) || artifactGuess.contains(symbolicName)) {
                return bundle;
            }
        }
        return null;
    }
}
