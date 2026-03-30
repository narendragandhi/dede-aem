package com.dede.aem.servlets;

import com.dede.aem.models.CodeNode;
import com.dede.aem.models.NodeType;
import com.dede.aem.services.DedeGraphService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletPaths;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sling Servlet providing REST API for the Dede Graph Service.
 *
 * Endpoints:
 * - GET /bin/dede/graph - Full graph JSON
 * - GET /bin/dede/graph/stats - Graph statistics
 * - GET /bin/dede/graph/nodes?type=CLASS - Nodes by type
 * - GET /bin/dede/graph/node/{id} - Single node
 * - GET /bin/dede/graph/cycles - Detect circular dependencies
 * - GET /bin/dede/graph/suggestions - Refactoring suggestions
 * - POST /bin/dede/graph/scan?path=/apps/myproject - Scan JCR path
 * - DELETE /bin/dede/graph - Clear graph
 */
@Component(service = Servlet.class)
@SlingServletPaths({
    "/bin/dede/graph",
    "/bin/dede/graph/stats",
    "/bin/dede/graph/nodes",
    "/bin/dede/graph/cycles",
    "/bin/dede/graph/suggestions",
    "/bin/dede/graph/scan"
})
public class DedeGraphServlet extends SlingAllMethodsServlet {

    private static final Logger LOG = LoggerFactory.getLogger(DedeGraphServlet.class);
    private static final String CONTENT_TYPE_JSON = "application/json;charset=UTF-8";

    @Reference
    private DedeGraphService graphService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType(CONTENT_TYPE_JSON);
        String path = request.getRequestURI();

        try {
            if (path.endsWith("/graph/stats")) {
                handleStats(response);
            } else if (path.endsWith("/graph/nodes")) {
                handleNodes(request, response);
            } else if (path.endsWith("/graph/cycles")) {
                handleCycles(response);
            } else if (path.endsWith("/graph/suggestions")) {
                handleSuggestions(response);
            } else if (path.endsWith("/graph")) {
                handleFullGraph(response);
            } else {
                response.setStatus(404);
                writeJson(response, errorJson("Not found"));
            }
        } catch (Exception e) {
            LOG.error("Error handling GET request", e);
            response.setStatus(500);
            writeJson(response, errorJson(e.getMessage()));
        }
    }

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType(CONTENT_TYPE_JSON);
        String path = request.getRequestURI();

        try {
            if (path.endsWith("/graph/scan")) {
                handleScan(request, response);
            } else {
                response.setStatus(404);
                writeJson(response, errorJson("Not found"));
            }
        } catch (Exception e) {
            LOG.error("Error handling POST request", e);
            response.setStatus(500);
            writeJson(response, errorJson(e.getMessage()));
        }
    }

    @Override
    protected void doDelete(SlingHttpServletRequest request, SlingHttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType(CONTENT_TYPE_JSON);
        String path = request.getRequestURI();

        try {
            if (path.endsWith("/graph")) {
                graphService.clear();
                writeJson(response, successJson("Graph cleared"));
            } else {
                response.setStatus(404);
                writeJson(response, errorJson("Not found"));
            }
        } catch (Exception e) {
            LOG.error("Error handling DELETE request", e);
            response.setStatus(500);
            writeJson(response, errorJson(e.getMessage()));
        }
    }

    private void handleFullGraph(SlingHttpServletResponse response) throws IOException {
        String json = graphService.exportToJson();
        response.getWriter().write(json);
    }

    private void handleStats(SlingHttpServletResponse response) throws IOException {
        Map<String, Object> stats = graphService.getStatistics();
        writeJson(response, objectMapper.writeValueAsString(stats));
    }

    private void handleNodes(SlingHttpServletRequest request, SlingHttpServletResponse response)
            throws IOException {

        String typeParam = request.getParameter("type");
        List<CodeNode> nodes;

        if (typeParam != null && !typeParam.isEmpty()) {
            try {
                NodeType type = NodeType.valueOf(typeParam.toUpperCase());
                nodes = graphService.findNodesByType(type);
            } catch (IllegalArgumentException e) {
                response.setStatus(400);
                writeJson(response, errorJson("Invalid node type: " + typeParam));
                return;
            }
        } else {
            nodes = List.copyOf(graphService.getAllNodes());
        }

        ArrayNode nodesArray = objectMapper.createArrayNode();
        for (CodeNode node : nodes) {
            ObjectNode nodeJson = objectMapper.createObjectNode();
            nodeJson.put("id", node.getId());
            nodeJson.put("name", node.getName());
            nodeJson.put("type", node.getType().name());
            nodeJson.put("path", node.getPath());
            nodesArray.add(nodeJson);
        }

        ObjectNode result = objectMapper.createObjectNode();
        result.put("count", nodes.size());
        result.set("nodes", nodesArray);
        writeJson(response, objectMapper.writeValueAsString(result));
    }

    private void handleCycles(SlingHttpServletResponse response) throws IOException {
        Set<Set<CodeNode>> cycles = graphService.findCycles();

        ArrayNode cyclesArray = objectMapper.createArrayNode();
        for (Set<CodeNode> cycle : cycles) {
            ArrayNode cycleArray = objectMapper.createArrayNode();
            for (CodeNode node : cycle) {
                cycleArray.add(node.getId());
            }
            cyclesArray.add(cycleArray);
        }

        ObjectNode result = objectMapper.createObjectNode();
        result.put("count", cycles.size());
        result.set("cycles", cyclesArray);

        if (!cycles.isEmpty()) {
            result.put("warning", "Circular dependencies detected! This can cause OSGi startup issues.");
        }

        writeJson(response, objectMapper.writeValueAsString(result));
    }

    private void handleSuggestions(SlingHttpServletResponse response) throws IOException {
        List<String> suggestions = graphService.suggestRefactoring();

        ArrayNode suggestionsArray = objectMapper.createArrayNode();
        for (String suggestion : suggestions) {
            suggestionsArray.add(suggestion);
        }

        ObjectNode result = objectMapper.createObjectNode();
        result.put("count", suggestions.size());
        result.set("suggestions", suggestionsArray);
        writeJson(response, objectMapper.writeValueAsString(result));
    }

    private void handleScan(SlingHttpServletRequest request, SlingHttpServletResponse response)
            throws IOException {

        String scanPath = request.getParameter("path");
        if (scanPath == null || scanPath.isEmpty()) {
            scanPath = "/apps";
        }

        boolean clear = "true".equals(request.getParameter("clear"));
        if (clear) {
            graphService.clear();
        }

        LOG.info("Scanning JCR path: {}", scanPath);
        long startTime = System.currentTimeMillis();
        graphService.scanJcrPath(scanPath);
        long duration = System.currentTimeMillis() - startTime;

        ObjectNode result = objectMapper.createObjectNode();
        result.put("success", true);
        result.put("scannedPath", scanPath);
        result.put("nodeCount", graphService.getNodeCount());
        result.put("edgeCount", graphService.getEdgeCount());
        result.put("durationMs", duration);

        writeJson(response, objectMapper.writeValueAsString(result));
    }

    private void writeJson(SlingHttpServletResponse response, String json) throws IOException {
        response.getWriter().write(json);
    }

    private String errorJson(String message) {
        return "{\"error\": \"" + message.replace("\"", "\\\"") + "\"}";
    }

    private String successJson(String message) {
        return "{\"success\": true, \"message\": \"" + message.replace("\"", "\\\"") + "\"}";
    }
}
