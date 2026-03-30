package com.dede.api;

import com.dede.domain.GraphService;
import com.dede.domain.model.CodeNode;
import com.dede.exception.ErrorCode;
import com.dede.exception.GraphException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/graph")
@Tag(name = "Graph API", description = "Endpoints for accessing the architectural dependency graph")
public class GraphController {

    private static final Logger log = LoggerFactory.getLogger(GraphController.class);

    private final GraphService graphService;

    public GraphController(GraphService graphService) {
        this.graphService = graphService;
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
