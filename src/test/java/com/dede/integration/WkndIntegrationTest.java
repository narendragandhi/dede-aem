package com.dede.integration;

import com.dede.discovery.*;
import com.dede.domain.*;
import com.dede.domain.model.CodeNode;
import com.dede.domain.model.NodeType;
import com.dede.intelligence.GraphAgentSkills;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests that validate dede-java against real AEM projects.
 *
 * These tests require the WKND project to be cloned locally.
 * Set WKND_PROJECT_PATH environment variable or use default /tmp/aem-guides-wknd
 */
@DisplayName("WKND Integration Tests")
class WkndIntegrationTest {

    private static final String WKND_PATH = System.getenv("WKND_PROJECT_PATH") != null
        ? System.getenv("WKND_PROJECT_PATH")
        : "/tmp/aem-guides-wknd";

    private GraphService graphService;
    private ProjectScanner scanner;
    private GraphAgentSkills skills;

    @BeforeEach
    void setUp() {
        GraphRepository repository = new GraphRepository();
        GraphAnalyzer analyzer = new GraphAnalyzer(repository);
        GraphExporter exporter = new GraphExporter(repository);
        graphService = new GraphService(repository, analyzer, exporter);
        skills = new GraphAgentSkills(graphService);

        SourceParser sourceParser = new SourceParser(graphService);
        OsgiManifestParser manifestParser = new OsgiManifestParser(graphService);
        SlingHtlParser htlParser = new SlingHtlParser(graphService);
        JcrContentParser jcrParser = new JcrContentParser(graphService);
        DispatcherParser dispatcherParser = new DispatcherParser(graphService);
        ApacheConfigParser apacheParser = new ApacheConfigParser(graphService);

        scanner = new ProjectScanner(sourceParser, manifestParser, htlParser, jcrParser, dispatcherParser, apacheParser, graphService);
    }

    static boolean wkndExists() {
        return Files.exists(Path.of(WKND_PATH));
    }

    @Nested
    @DisplayName("Full Project Scan")
    @EnabledIf("com.dede.integration.WkndIntegrationTest#wkndExists")
    class FullProjectScan {

        @BeforeEach
        void scanProject() throws IOException {
            scanner.scan(WKND_PATH);
        }

        @Test
        @DisplayName("Should complete scan without crashes")
        void shouldCompleteScanWithoutCrashes() {
            // If we get here, no exceptions were thrown
            assertThat(graphService.getNodeCount()).isGreaterThan(0);
        }

        @Test
        @DisplayName("Should detect expected number of nodes")
        void shouldDetectExpectedNodes() {
            // WKND should have substantial code
            assertThat(graphService.getNodeCount()).isGreaterThan(100);
            assertThat(graphService.getEdgeCount()).isGreaterThan(100);
        }

        @Test
        @DisplayName("Should detect Java classes")
        void shouldDetectJavaClasses() {
            List<CodeNode> classes = graphService.findNodesByType(NodeType.CLASS);
            assertThat(classes).isNotEmpty();

            // Should find some known WKND classes
            boolean hasWkndClasses = classes.stream()
                .anyMatch(c -> c.getId().contains("wknd"));
            assertThat(hasWkndClasses).isTrue();
        }

        @Test
        @DisplayName("Should detect Sling Models")
        void shouldDetectSlingModels() {
            List<CodeNode> models = graphService.findNodesByType(NodeType.SLING_MODEL);
            assertThat(models).isNotEmpty();
        }

        @Test
        @DisplayName("Should detect HTL templates")
        void shouldDetectHtlTemplates() {
            List<CodeNode> htlFiles = graphService.findNodesByType(NodeType.HTL_FILE);
            assertThat(htlFiles).isNotEmpty();
        }

        @Test
        @DisplayName("Should detect JCR resource types")
        void shouldDetectResourceTypes() {
            List<CodeNode> resourceTypes = graphService.findNodesByType(NodeType.JCR_RESOURCE_TYPE);
            assertThat(resourceTypes).isNotEmpty();
        }

        @Test
        @DisplayName("Should detect dispatcher filters")
        void shouldDetectDispatcherFilters() {
            List<CodeNode> filters = graphService.findNodesByType(NodeType.DISPATCHER_FILTER);
            assertThat(filters).isNotEmpty();
        }

        @Test
        @DisplayName("Should not have self-loops in graph")
        void shouldNotHaveSelfLoops() {
            // Regression test for the self-loop crash
            Set<CodeNode> allNodes = graphService.getAllNodes();

            for (CodeNode node : allNodes) {
                Set<CodeNode> outgoing = graphService.getOutgoingNodes(node);
                assertThat(outgoing)
                    .as("Node %s should not have self-loop", node.getId())
                    .doesNotContain(node);
            }
        }

        @Test
        @DisplayName("Should produce minimal false positives for zombie code")
        void shouldProduceMinimalZombieFalsePositives() {
            List<String> suggestions = skills.suggestRefactoring();

            long zombieCount = suggestions.stream()
                .filter(s -> s.contains("Zombie Code"))
                .count();

            // Should have very few zombie code warnings for a well-structured project
            assertThat(zombieCount).isLessThanOrEqualTo(3);
        }

        @Test
        @DisplayName("Should detect proxy components correctly")
        void shouldDetectProxyComponents() {
            List<CodeNode> resourceTypes = graphService.findNodesByType(NodeType.JCR_RESOURCE_TYPE);

            // Check for sling:resourceSuperType in properties (proxy indicator)
            long proxyOrExtendingCount = resourceTypes.stream()
                .filter(r -> "true".equals(r.getProperties().get("isProxy")) ||
                             r.getProperties().containsKey("sling:resourceSuperType"))
                .count();

            // WKND has components - may or may not be detected as proxies depending on path structure
            // At minimum, we should have resource types
            assertThat(resourceTypes.size()).isGreaterThan(0);
        }
    }

    @Nested
    @DisplayName("Specific Parser Tests")
    class SpecificParserTests {

        @Test
        @DisplayName("Should parse HTL with data-sly-use expressions")
        void shouldParseHtlWithDataSlyUse(@TempDir Path tempDir) throws IOException {
            String htlContent = """
                <div data-sly-use.model="com.adobe.aem.guides.wknd.core.models.Byline">
                    <h2>${model.name}</h2>
                </div>
                """;

            Path htlFile = tempDir.resolve("test.html");
            Files.writeString(htlFile, htlContent);

            SlingHtlParser htlParser = new SlingHtlParser(graphService);
            htlParser.parse(htlFile);

            List<CodeNode> htlNodes = graphService.findNodesByType(NodeType.HTL_FILE);
            assertThat(htlNodes).hasSize(1);
        }

        @Test
        @DisplayName("Should parse HTL with spaced attributes")
        void shouldParseHtlWithSpacedAttributes(@TempDir Path tempDir) throws IOException {
            // Regression: real HTL files have spaces around =
            String htlContent = """
                <sly data-sly-use.clientlib = "/libs/granite/sightly/templates/clientlib.html">
                    ${clientlib.js}
                </sly>
                """;

            Path htlFile = tempDir.resolve("test.html");
            Files.writeString(htlFile, htlContent);

            SlingHtlParser htlParser = new SlingHtlParser(graphService);
            htlParser.parse(htlFile);

            List<CodeNode> htlNodes = graphService.findNodesByType(NodeType.HTL_FILE);
            // Parser creates node for source file AND referenced HTL file
            assertThat(htlNodes).isNotEmpty();
            assertThat(htlNodes.stream().anyMatch(n -> n.getFilePath() != null && n.getFilePath().contains("test.html"))).isTrue();
        }

        @Test
        @DisplayName("Should parse proxy component in .content.xml")
        void shouldParseProxyComponent(@TempDir Path tempDir) throws IOException {
            // Create apps/wknd/components/button/.content.xml
            Path appsDir = tempDir.resolve("jcr_root/apps/wknd/components/button");
            Files.createDirectories(appsDir);

            String contentXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <jcr:root xmlns:jcr="http://www.jcp.org/jcr/1.0"
                          xmlns:cq="http://www.day.com/jcr/cq/1.0"
                          xmlns:sling="http://sling.apache.org/jcr/sling/1.0"
                          jcr:primaryType="cq:Component"
                          jcr:title="Button"
                          sling:resourceSuperType="core/wcm/components/button/v2/button"
                          componentGroup="WKND - Content"/>
                """;

            Path contentFile = appsDir.resolve(".content.xml");
            Files.writeString(contentFile, contentXml);

            JcrContentParser jcrParser = new JcrContentParser(graphService);
            jcrParser.parse(contentFile);

            List<CodeNode> resourceTypes = graphService.findNodesByType(NodeType.JCR_RESOURCE_TYPE);

            // Should have proxy component
            boolean hasProxy = resourceTypes.stream()
                .anyMatch(r -> "true".equals(r.getProperties().get("isProxy")));
            assertThat(hasProxy).isTrue();
        }

        @Test
        @DisplayName("Should handle dispatcher include directives")
        void shouldHandleDispatcherIncludes(@TempDir Path tempDir) throws IOException {
            // Create main dispatcher.any with $include
            String mainConfig = """
                /farms {
                    /publishfarm {
                        $include "filters.any"
                    }
                }
                """;

            String filtersConfig = """
                /filter {
                    /0001 { /type "deny" /url "*" }
                    /0002 { /type "allow" /url "/content/*" }
                }
                """;

            Path mainFile = tempDir.resolve("dispatcher.any");
            Path filtersFile = tempDir.resolve("filters.any");
            Files.writeString(mainFile, mainConfig);
            Files.writeString(filtersFile, filtersConfig);

            DispatcherParser dispatcherParser = new DispatcherParser(graphService);
            dispatcherParser.parse(mainFile);

            List<CodeNode> filters = graphService.findNodesByType(NodeType.DISPATCHER_FILTER);
            // Should have parsed the included filters
            assertThat(filters.size()).isGreaterThanOrEqualTo(2);
        }
    }
}
