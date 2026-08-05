package com.dede.api;

import com.dede.discovery.ApacheConfigParser;
import com.dede.discovery.DispatcherParser;
import com.dede.discovery.JcrContentParser;
import com.dede.discovery.OsgiLinker;
import com.dede.discovery.OsgiManifestParser;
import com.dede.discovery.ProjectScanner;
import com.dede.discovery.SlingHtlParser;
import com.dede.discovery.SourceParser;
import com.dede.domain.GraphAnalyzer;
import com.dede.domain.GraphExporter;
import com.dede.domain.GraphRepository;
import com.dede.domain.GraphService;
import com.dede.domain.model.CodeNode;
import com.dede.domain.model.NodeType;
import com.dede.domain.model.RelationshipType;
import com.dede.exception.ErrorCode;
import com.dede.exception.GraphException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("GraphController Unit Tests")
class GraphControllerTest {

    private GraphController controller;
    private GraphService graphService;

    @BeforeEach
    void setUp() {
        GraphRepository repository = new GraphRepository();
        GraphAnalyzer analyzer = new GraphAnalyzer(repository);
        GraphExporter exporter = new GraphExporter(repository);
        graphService = new GraphService(repository, analyzer, exporter);
        SourceParser sourceParser = new SourceParser(graphService);
        ProjectScanner scanner = new ProjectScanner(
            sourceParser,
            new OsgiManifestParser(graphService),
            new SlingHtlParser(graphService),
            new JcrContentParser(graphService),
            new DispatcherParser(graphService),
            new ApacheConfigParser(graphService),
            graphService
        );
        controller = new GraphController(graphService, scanner, sourceParser, new OsgiLinker(graphService));

        // Add sample data
        CodeNode pkg = new CodeNode("pkg:com.test", "com.test", NodeType.PACKAGE, "com.test", null);
        CodeNode clazz = new CodeNode("class:TestClass", "TestClass", NodeType.CLASS, "com.test.TestClass", "/src/Test.java");
        graphService.addEdge(pkg, clazz, RelationshipType.CONTAINS);
    }

    @Test
    @DisplayName("getGraph should return JSON graph")
    void shouldReturnGraph() {
        ResponseEntity<String> response = controller.getGraph();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotBlank();
    }

    @Test
    @DisplayName("getStats should return node and edge counts")
    void shouldReturnStats() {
        ResponseEntity<Map<String, Object>> response = controller.getStats();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).containsKey("nodeCount");
        assertThat(response.getBody()).containsKey("edgeCount");
        assertThat((Integer) response.getBody().get("nodeCount")).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("getNode should return node when found")
    void shouldReturnNodeWhenFound() {
        ResponseEntity<CodeNode> response = controller.getNode("class:TestClass");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getId()).isEqualTo("class:TestClass");
        assertThat(response.getBody().getName()).isEqualTo("TestClass");
    }

    @Test
    @DisplayName("getNode should throw GraphException when not found")
    void shouldThrowWhenNodeNotFound() {
        assertThatThrownBy(() -> controller.getNode("non:existent"))
            .isInstanceOf(GraphException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NODE_NOT_FOUND);
    }

    @Test
    @DisplayName("getIncomingNodes should return incoming nodes")
    void shouldReturnIncomingNodes() {
        ResponseEntity<Set<CodeNode>> response = controller.getIncomingNodes("class:TestClass");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().iterator().next().getId()).isEqualTo("pkg:com.test");
    }

    @Test
    @DisplayName("getOutgoingNodes should return outgoing nodes")
    void shouldReturnOutgoingNodes() {
        ResponseEntity<Set<CodeNode>> response = controller.getOutgoingNodes("pkg:com.test");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().iterator().next().getId()).isEqualTo("class:TestClass");
    }

    @Test
    @DisplayName("findCycles should return empty when no cycles")
    void shouldReturnEmptyWhenNoCycles() {
        ResponseEntity<Set<Set<CodeNode>>> response = controller.findCycles();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    @DisplayName("getHierarchicalGraph should return hierarchical JSON")
    void shouldReturnHierarchicalGraph() {
        ResponseEntity<String> response = controller.getHierarchicalGraph();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotBlank();
    }

    /**
     * No-args server mode (the README's own documented "run as a web server for
     * programmatic access") starts with a permanently empty graph: nothing calls
     * scanner.scan() unless the process is launched with a CLI project-path
     * argument, and that path always exits the process afterward (or requires
     * --watch). Confirmed by starting a real no-args server and hitting every
     * REST/GraphQL endpoint -- all returned nodeCount: 0. This endpoint is what
     * makes no-args server mode actually usable as documented.
     */
    @Test
    @DisplayName("scan should populate the graph from a real project path within the allowed root")
    void scanShouldPopulateGraph(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws java.io.IOException {
        java.nio.file.Files.writeString(tempDir.resolve("ScannedClass.java"), """
                package com.scanned;

                public class ScannedClass {
                    public void doWork() {}
                }
                """);
        controller.setAllowedRoot(tempDir.toString());

        ResponseEntity<Map<String, Object>> response =
            controller.scan(new GraphController.ScanRequest(tempDir.toString(), "aem"));

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        // 2 pre-existing nodes from setUp() (pkg:com.test, class:TestClass) + the
        // newly scanned package/class/method.
        assertThat((Integer) response.getBody().get("nodeCount")).isGreaterThanOrEqualTo(4);
        assertThat(graphService.findNodeById("class:com.scanned.ScannedClass")).isPresent();
    }

    /**
     * This API has no authentication (a separate, already-known gap). Without
     * this restriction, any caller who can reach the endpoint could direct the
     * server to walk an arbitrary filesystem path -- confirmed by actually
     * calling the running server with {"projectPath": "/etc"} before this check
     * existed. Fail-closed: no allowed root configured means no scanning at all.
     */
    @Test
    @DisplayName("scan should reject every request when no allowed root is configured")
    void scanRejectsWhenNoAllowedRootConfigured(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) {
        controller.setAllowedRoot(null);

        ResponseEntity<Map<String, Object>> response =
            controller.scan(new GraphController.ScanRequest(tempDir.toString(), "aem"));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    @DisplayName("scan should reject a path outside the configured allowed root")
    void scanRejectsPathOutsideAllowedRoot(@org.junit.jupiter.api.io.TempDir java.nio.file.Path allowedDir,
                                            @org.junit.jupiter.api.io.TempDir java.nio.file.Path outsideDir) {
        controller.setAllowedRoot(allowedDir.toString());

        ResponseEntity<Map<String, Object>> response =
            controller.scan(new GraphController.ScanRequest(outsideDir.toString(), "aem"));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    @DisplayName("scan should reject a path traversal attempt escaping the allowed root")
    void scanRejectsPathTraversalEscapingAllowedRoot(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws java.io.IOException {
        java.nio.file.Path allowedSubdir = tempDir.resolve("allowed");
        java.nio.file.Files.createDirectories(allowedSubdir);
        controller.setAllowedRoot(allowedSubdir.toString());

        // "allowed/../.." normalizes to somewhere above the allowed root.
        ResponseEntity<Map<String, Object>> response =
            controller.scan(new GraphController.ScanRequest(allowedSubdir.resolve("../..").toString(), "aem"));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }
}
