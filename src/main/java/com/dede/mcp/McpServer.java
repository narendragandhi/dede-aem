package com.dede.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * A minimal Model Context Protocol server over stdio: newline-delimited JSON-RPC 2.0, tools
 * only. Enough for Claude Code, Claude Desktop and other MCP clients to call dede's tools; no
 * resources, prompts or sampling.
 *
 * <p>stdout carries the protocol and nothing else -- logging must go to stderr while it runs.
 */
public final class McpServer {

    private static final Logger log = LoggerFactory.getLogger(McpServer.class);
    static final String LATEST_PROTOCOL = "2025-06-18";
    private static final Set<String> SUPPORTED_PROTOCOLS = Set.of("2025-06-18", "2025-03-26", "2024-11-05");

    /** A tool: JSON Schema for its arguments, and a handler returning a JSON-serializable result. */
    public record Tool(String name, String description, Map<String, Object> inputSchema,
                       Function<JsonNode, Object> handler) {}

    /** Thrown by a handler for a bad argument: reported to the model as a tool error, not a crash. */
    public static final class ToolError extends RuntimeException {
        public ToolError(String message) { super(message); }
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final String serverName;
    private final String serverVersion;

    public McpServer(String serverName, String serverVersion, List<Tool> tools) {
        this.serverName = serverName;
        this.serverVersion = serverVersion;
        tools.forEach(t -> this.tools.put(t.name(), t));
    }

    /** Serves until {@code in} is closed. */
    public void serve(InputStream in, PrintStream out) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) continue;
            String response = handle(line);
            if (response != null) {
                out.println(response);
                out.flush();
            }
        }
    }

    /** One request line -> one response line, or null for notifications. */
    String handle(String line) {
        JsonNode request;
        try {
            request = mapper.readTree(line);
        } catch (IOException e) {
            return error(null, -32700, "Parse error: not a JSON-RPC message");
        }
        JsonNode id = request.get("id");
        String method = request.path("method").asText("");
        if (id == null || id.isNull()) {
            return null;   // notification (notifications/initialized, notifications/cancelled, ...)
        }
        try {
            Object result = switch (method) {
                case "initialize" -> initialize(request.path("params"));
                case "ping" -> Map.of();
                case "tools/list" -> Map.of("tools", tools.values().stream().map(t -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", t.name());
                    row.put("description", t.description());
                    row.put("inputSchema", t.inputSchema());
                    return row;
                }).toList());
                case "tools/call" -> call(request.path("params"));
                default -> null;
            };
            if (result == null) {
                return error(id, -32601, "Method not found: " + method);
            }
            ObjectNode response = mapper.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", id);
            response.set("result", mapper.valueToTree(result));
            return mapper.writeValueAsString(response);
        } catch (UnknownToolException e) {
            return error(id, -32602, e.getMessage());
        } catch (Exception e) {
            log.warn("MCP request {} failed", method, e);
            return error(id, -32603, "Internal error: " + e.getMessage());
        }
    }

    private Map<String, Object> initialize(JsonNode params) {
        String requested = params.path("protocolVersion").asText(LATEST_PROTOCOL);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", SUPPORTED_PROTOCOLS.contains(requested) ? requested : LATEST_PROTOCOL);
        result.put("capabilities", Map.of("tools", Map.of("listChanged", false)));
        result.put("serverInfo", Map.of("name", serverName, "version", serverVersion));
        result.put("instructions", "Dede has scanned an AEM project into a code graph, with Cloud Service "
            + "readiness findings and, when it was given traces or AEM logs, runtime data (calls, requests, "
            + "errors). Start with `summary`, then drill down with `list_findings`, `class_info`, `impact` "
            + "and `search`.");
        return result;
    }

    private static final class UnknownToolException extends RuntimeException {
        UnknownToolException(String message) { super(message); }
    }

    private Map<String, Object> call(JsonNode params) throws IOException {
        String name = params.path("name").asText("");
        Tool tool = tools.get(name);
        if (tool == null) throw new UnknownToolException("Unknown tool: " + name);
        JsonNode args = params.path("arguments");
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Object value = tool.handler().apply(args.isMissingNode() ? mapper.createObjectNode() : args);
            String text = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
            result.put("content", List.of(Map.of("type", "text", "text", text)));
            if (value instanceof Map<?, ?>) result.put("structuredContent", value);
            result.put("isError", false);
        } catch (ToolError e) {
            result.put("content", List.of(Map.of("type", "text", "text", e.getMessage())));
            result.put("isError", true);
        }
        return result;
    }

    private String error(JsonNode id, int code, String message) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (id == null) response.putNull("id"); else response.set("id", id);
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        try {
            return mapper.writeValueAsString(response);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
