package com.dede.security;

import com.dede.domain.GraphAnalyzer;
import com.dede.domain.GraphExporter;
import com.dede.domain.GraphRepository;
import com.dede.domain.GraphService;
import com.dede.domain.model.CodeNode;
import com.dede.domain.model.NodeType;
import com.dede.domain.model.RelationshipType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DependencyCveImporterTest {

    private GraphService graphService;
    private DependencyCveImporter importer;

    @TempDir Path tempDir;

    @BeforeEach
    void setUp() {
        GraphRepository repo = new GraphRepository();
        graphService = new GraphService(repo, new GraphAnalyzer(repo), new GraphExporter(repo));
        importer = new DependencyCveImporter(graphService);
    }

    private Path writeReport(String json) throws IOException {
        Path file = tempDir.resolve("dependency-check-report.json");
        Files.writeString(file, json);
        return file;
    }

    @Test
    void importsVulnerabilityAndLinksToMatchingBundle() throws IOException {
        CodeNode bundle = new CodeNode("bundle:commons-collections", "commons-collections",
            NodeType.BUNDLE, "commons-collections:3.2.1", null);
        graphService.addNode(bundle);

        Path report = writeReport("""
            {
              "dependencies": [
                {
                  "fileName": "commons-collections-3.2.1.jar",
                  "filePath": "/project/lib/commons-collections-3.2.1.jar",
                  "vulnerabilities": [
                    {
                      "name": "CVE-2015-6420",
                      "severity": "HIGH",
                      "cvssv3": { "baseScore": 7.5 }
                    }
                  ]
                }
              ]
            }
            """);

        int imported = importer.importReport(report);

        assertThat(imported).isEqualTo(1);
        List<CodeNode> vulnNodes = graphService.findNodesByType(NodeType.VULNERABILITY);
        assertThat(vulnNodes).hasSize(1);

        CodeNode vulnNode = vulnNodes.get(0);
        assertThat(vulnNode.getProperties())
            .containsEntry("cveId", "CVE-2015-6420")
            .containsEntry("severity", "HIGH")
            .containsEntry("cvssScore", "7.5")
            .containsEntry("dependency", "commons-collections-3.2.1.jar");

        Set<CodeNode> targets = graphService.getOutgoingNodes(vulnNode);
        assertThat(targets).contains(bundle);
    }

    @Test
    void dependencyWithNoVulnerabilitiesIsSkipped() throws IOException {
        Path report = writeReport("""
            {
              "dependencies": [
                { "fileName": "clean-lib-1.0.jar", "vulnerabilities": [] }
              ]
            }
            """);

        int imported = importer.importReport(report);

        assertThat(imported).isZero();
        assertThat(graphService.findNodesByType(NodeType.VULNERABILITY)).isEmpty();
    }

    @Test
    void unmatchedDependencyStillRecordsVulnerabilityWithoutLink() throws IOException {
        // No BUNDLE node exists for this dependency at all.
        Path report = writeReport("""
            {
              "dependencies": [
                {
                  "fileName": "orphan-lib-2.0.jar",
                  "vulnerabilities": [
                    { "name": "CVE-2024-9999", "severity": "CRITICAL", "cvssv3": { "baseScore": 9.8 } }
                  ]
                }
              ]
            }
            """);

        int imported = importer.importReport(report);

        assertThat(imported).isEqualTo(1);
        CodeNode vulnNode = graphService.findNodesByType(NodeType.VULNERABILITY).get(0);
        assertThat(graphService.getOutgoingNodes(vulnNode)).isEmpty();
    }

    @Test
    void fallsBackToCvssv2WhenV3Missing() throws IOException {
        Path report = writeReport("""
            {
              "dependencies": [
                {
                  "fileName": "legacy-lib-1.0.jar",
                  "vulnerabilities": [
                    { "name": "CVE-2010-0001", "severity": "MEDIUM", "cvssv2": { "score": 5.0 } }
                  ]
                }
              ]
            }
            """);

        importer.importReport(report);

        CodeNode vulnNode = graphService.findNodesByType(NodeType.VULNERABILITY).get(0);
        assertThat(vulnNode.getProperties()).containsEntry("cvssScore", "5.0");
    }

    @Test
    void multipleCvesOnSameDependencyEachBecomeANode() throws IOException {
        Path report = writeReport("""
            {
              "dependencies": [
                {
                  "fileName": "multi-cve-lib-1.0.jar",
                  "vulnerabilities": [
                    { "name": "CVE-2024-1111", "severity": "LOW", "cvssv3": { "baseScore": 3.1 } },
                    { "name": "CVE-2024-2222", "severity": "HIGH", "cvssv3": { "baseScore": 8.2 } }
                  ]
                }
              ]
            }
            """);

        int imported = importer.importReport(report);

        assertThat(imported).isEqualTo(2);
        assertThat(graphService.findNodesByType(NodeType.VULNERABILITY))
            .extracting(n -> n.getProperties().get("cveId"))
            .containsExactlyInAnyOrder("CVE-2024-1111", "CVE-2024-2222");
    }

    @Test
    void malformedReportReturnsZeroWithoutThrowing() throws IOException {
        Path report = writeReport("{ \"notADependencyCheckReport\": true }");

        int imported = importer.importReport(report);

        assertThat(imported).isZero();
    }
}
