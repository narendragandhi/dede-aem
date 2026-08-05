package com.dede.api;

import com.dede.discovery.OsgiLinker;
import com.dede.discovery.ProjectScanner;
import com.dede.discovery.SourceParser;
import com.dede.domain.GraphService;
import com.dede.domain.model.CodeNode;
import com.dede.exception.ErrorCode;
import com.dede.exception.GraphException;
import com.dede.exception.ParseException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/graph")
@Tag(name = "Graph API", description = "Endpoints for accessing the architectural dependency graph")
public class GraphController {

    private static final Logger log = LoggerFactory.getLogger(GraphController.class);

    private final GraphService graphService;
    private final ProjectScanner scanner;
    private final SourceParser sourceParser;
    private final OsgiLinker osgiLinker;

    @Value("${dede.scan.allowed-root:}")
    private String allowedRoot;

    public GraphController(GraphService graphService, ProjectScanner scanner,
                           SourceParser sourceParser, OsgiLinker osgiLinker) {
        this.graphService = graphService;
        this.scanner = scanner;
        this.sourceParser = sourceParser;
        this.osgiLinker = osgiLinker;
    }

    public record ScanRequest(String projectPath, String profiles) {}

    /**
     * When run in no-args "server mode" (the README's own documented way to
     * "run as a web server for programmatic access"), the graph starts and stays
     * permanently empty: nothing calls scanner.scan() unless the process is
     * launched with a CLI project-path argument, and that path always exits the
     * process afterward (or requires --watch). This endpoint is what actually
     * makes no-args server mode usable as documented -- confirmed by hitting
     * every REST/GraphQL endpoint against a freshly started no-args server and
     * getting nodeCount: 0 back from all of them.
     *
     * Fails closed on the path: this API has no authentication (a separate,
     * already-known gap), so without a restriction here any caller who can reach
     * it could direct the server to walk an arbitrary filesystem path -- confirmed
     * by actually calling this with {"projectPath": "/etc"} before adding the
     * check. Requires dede.scan.allowed-root (DEDE_SCAN_ALLOWED_ROOT) to be set;
     * rejects every request otherwise, and rejects any path that normalizes to
     * somewhere outside that root.
     */
    @PostMapping(value = "/scan", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Scan a project", description = "Scans the given project path and (re)populates the dependency graph. Disabled unless DEDE_SCAN_ALLOWED_ROOT is configured.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Scan completed successfully"),
        @ApiResponse(responseCode = "403", description = "Scanning disabled, or requested path is outside the configured allowed root"),
        @ApiResponse(responseCode = "422", description = "Scan failed (invalid path or unreadable project)")
    })
    public ResponseEntity<Map<String, Object>> scan(@RequestBody ScanRequest request) {
        String projectPath = request.projectPath();
        String profiles = request.profiles() != null ? request.profiles() : "aem";

        if (allowedRoot == null || allowedRoot.isBlank()) {
            log.warn("Rejected scan request for {}: DEDE_SCAN_ALLOWED_ROOT is not configured", projectPath);
            return forbidden("Server-side scanning is disabled. Set DEDE_SCAN_ALLOWED_ROOT to the directory "
                + "you want to allow scanning under to enable this endpoint.");
        }

        Path requested = Path.of(projectPath).toAbsolutePath().normalize();
        Path allowed = Path.of(allowedRoot).toAbsolutePath().normalize();
        if (!requested.equals(allowed) && !requested.startsWith(allowed)) {
            log.warn("Rejected scan request for {}: outside allowed root {}", requested, allowed);
            return forbidden("Requested path is outside the configured allowed root.");
        }

        log.info("Scanning project via API: {} (profiles={})", requested, profiles);
        try {
            sourceParser.loadProfiles(profiles.split(","));
            scanner.scan(requested.toString());
            osgiLinker.link();
        } catch (IOException e) {
            throw new ParseException(ErrorCode.PARSE_ERROR,
                "Failed to scan project: " + e.getMessage(), requested, e);
        }

        log.info("Scan complete via API: {} nodes, {} edges", graphService.getNodeCount(), graphService.getEdgeCount());
        return ResponseEntity.ok(Map.of(
            "nodeCount", graphService.getNodeCount(),
            "edgeCount", graphService.getEdgeCount()
        ));
    }

    /** Test-only: production code sets this via @Value injection. */
    void setAllowedRoot(String allowedRoot) {
        this.allowedRoot = allowedRoot;
    }

    private ResponseEntity<Map<String, Object>> forbidden(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", HttpStatus.FORBIDDEN.value());
        body.put("error", HttpStatus.FORBIDDEN.getReasonPhrase());
        body.put("message", message);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get full graph", description = "Returns the complete dependency graph in JSON format")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Graph retrieved successfully"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<String> getGraph() {
        log.debug("Retrieving full graph");
        return ResponseEntity.ok(graphService.exportToJson());
    }

    @GetMapping(value = "/hierarchical", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get hierarchical graph", description = "Returns the dependency graph in hierarchical JSON format")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Hierarchical graph retrieved successfully"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<String> getHierarchicalGraph() {
        log.debug("Retrieving hierarchical graph");
        return ResponseEntity.ok(graphService.exportHierarchicalJson());
    }

    @GetMapping(value = "/delta", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get graph delta", description = "Returns changes in the graph since a previous scan")
    public ResponseEntity<String> getDelta(@RequestParam(required = false) String since) {
        log.debug("Retrieving graph delta since: {}", since);
        // Simplified: if 'since' is provided, we'd normally compare snapshots.
        // For now, return the full graph as the 'initial' delta if since is null.
        return ResponseEntity.ok(graphService.exportToJson());
    }

    @GetMapping("/stats")
    @Operation(summary = "Get graph statistics", description = "Returns basic statistics about the dependency graph")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Statistics retrieved successfully")
    })
    public ResponseEntity<Map<String, Object>> getStats() {
        log.debug("Retrieving graph statistics");
        return ResponseEntity.ok(Map.of(
            "nodeCount", graphService.getNodeCount(),
            "edgeCount", graphService.getEdgeCount()
        ));
    }

    @GetMapping("/nodes/{nodeId}")
    @Operation(summary = "Get node by ID", description = "Returns a specific node from the graph by its ID")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Node found"),
        @ApiResponse(responseCode = "404", description = "Node not found")
    })
    public ResponseEntity<CodeNode> getNode(
            @Parameter(description = "The unique identifier of the node")
            @PathVariable String nodeId) {
        log.debug("Retrieving node: {}", nodeId);
        return graphService.findNodeById(nodeId)
            .map(ResponseEntity::ok)
            .orElseThrow(() -> new GraphException(ErrorCode.NODE_NOT_FOUND,
                "Node not found: " + nodeId, nodeId));
    }

    @GetMapping("/nodes/{nodeId}/incoming")
    @Operation(summary = "Get incoming nodes", description = "Returns all nodes that have edges pointing to the specified node")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Incoming nodes retrieved"),
        @ApiResponse(responseCode = "404", description = "Node not found")
    })
    public ResponseEntity<Set<CodeNode>> getIncomingNodes(
            @Parameter(description = "The unique identifier of the target node")
            @PathVariable String nodeId) {
        log.debug("Retrieving incoming nodes for: {}", nodeId);
        CodeNode node = graphService.findNodeById(nodeId)
            .orElseThrow(() -> new GraphException(ErrorCode.NODE_NOT_FOUND,
                "Node not found: " + nodeId, nodeId));
        return ResponseEntity.ok(graphService.getIncomingNodes(node));
    }

    @GetMapping("/nodes/{nodeId}/outgoing")
    @Operation(summary = "Get outgoing nodes", description = "Returns all nodes that the specified node points to")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Outgoing nodes retrieved"),
        @ApiResponse(responseCode = "404", description = "Node not found")
    })
    public ResponseEntity<Set<CodeNode>> getOutgoingNodes(
            @Parameter(description = "The unique identifier of the source node")
            @PathVariable String nodeId) {
        log.debug("Retrieving outgoing nodes for: {}", nodeId);
        CodeNode node = graphService.findNodeById(nodeId)
            .orElseThrow(() -> new GraphException(ErrorCode.NODE_NOT_FOUND,
                "Node not found: " + nodeId, nodeId));
        return ResponseEntity.ok(graphService.getOutgoingNodes(node));
    }

    @GetMapping("/cycles")
    @Operation(summary = "Find cycles", description = "Detects and returns all circular dependencies in the graph")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Cycle detection completed")
    })
    public ResponseEntity<Set<Set<CodeNode>>> findCycles() {
        log.info("Running cycle detection");
        Set<Set<CodeNode>> cycles = graphService.findCycles();
        log.info("Found {} cycles", cycles.size());
        return ResponseEntity.ok(cycles);
    }
}
