package com.dede.mcp;

import com.dede.domain.GraphAnalyzer;
import com.dede.domain.GraphExporter;
import com.dede.domain.GraphRepository;
import com.dede.domain.GraphService;
import com.dede.domain.model.CodeNode;
import com.dede.domain.model.NodeType;
import com.dede.domain.model.RelationshipType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpServerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private McpServer server;

    @BeforeEach
    void setUp() {
        GraphRepository repo = new GraphRepository();
        GraphService graph = new GraphService(repo, new GraphAnalyzer(repo), new GraphExporter(repo));
        CodeNode model = new CodeNode("class:com.acme.TeaserModel", "TeaserModel", NodeType.SLING_MODEL,
            "com.acme.TeaserModel", "/p/core/TeaserModel.java");
        model.getProperties().put("runtime.requests", "31");
        CodeNode servlet = new CodeNode("class:com.acme.ExportServlet", "ExportServlet", NodeType.SLING_SERVLET,
            "com.acme.ExportServlet", "/p/core/ExportServlet.java");
        servlet.getProperties().put("runtime.requests", "4");
        CodeNode util = new CodeNode("class:com.acme.Util", "Util", NodeType.CLASS, "com.acme.Util", "/p/core/Util.java");
        CodeNode otherUtil = new CodeNode("class:org.other.Util", "Util", NodeType.CLASS, "org.other.Util", null);
        for (CodeNode n : List.of(model, servlet, util, otherUtil)) graph.addNode(n);
        graph.addEdge(model, util, RelationshipType.CALLS);
        graph.addEdge(servlet, util, RelationshipType.CALLS);

        Map<String, Object> critical = new LinkedHashMap<>(Map.of("severity", "CRITICAL", "category", "ADMIN_SESSION",
            "file", "/p/core/Util.java", "line", 3, "runtimeCalls", 99L));
        Map<String, Object> medium = new LinkedHashMap<>(Map.of("severity", "MEDIUM", "category", "HARDCODED_PATHS",
            "file", "/p/core/Other.java", "line", 9));
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("project", "/p");
        report.put("summary", Map.of("total", 2));
        report.put("graph", Map.of("nodes", 4, "edges", 2));
        report.put("violations", List.of(medium, critical));
        report.put("requests", Map.of("total", 35));
        server = new McpServer("dede", "test", new DedeTools(graph, report).tools());
    }

    private JsonNode rpc(String json) throws Exception {
        String response = server.handle(json);
        return response == null ? null : mapper.readTree(response);
    }

    private JsonNode call(String tool, String arguments) throws Exception {
        JsonNode r = rpc("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
            + "\",\"arguments\":" + arguments + "}}");
        return r.path("result");
    }

    private JsonNode payload(JsonNode result) throws Exception {
        return mapper.readTree(result.path("content").get(0).path("text").asText());
    }

    @Test
    void handshakeAndToolList() throws Exception {
        JsonNode init = rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
            + "{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}");
        assertThat(init.path("result").path("protocolVersion").asText()).isEqualTo("2025-03-26");
        assertThat(init.path("result").path("serverInfo").path("name").asText()).isEqualTo("dede");
        assertThat(init.path("result").path("capabilities").has("tools")).isTrue();

        assertThat(rpc("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")).as("no reply").isNull();

        JsonNode list = rpc("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        List<String> names = new java.util.ArrayList<>();
        list.path("result").path("tools").forEach(t -> names.add(t.path("name").asText()));
        assertThat(names).containsExactly("summary", "list_findings", "class_info", "impact", "search");
        assertThat(list.path("result").path("tools").get(2).path("inputSchema").path("required").get(0).asText())
            .isEqualTo("name");
    }

    @Test
    void unknownProtocolVersionGetsTheLatest() throws Exception {
        JsonNode init = rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"1999-01-01\"}}");
        assertThat(init.path("result").path("protocolVersion").asText()).isEqualTo(McpServer.LATEST_PROTOCOL);
    }

    @Test
    void protocolErrors() throws Exception {
        assertThat(rpc("not json").path("error").path("code").asInt()).isEqualTo(-32700);
        assertThat(rpc("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"resources/list\"}").path("error").path("code").asInt())
            .isEqualTo(-32601);
        assertThat(rpc("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"nope\"}}")
            .path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(rpc("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"ping\"}").path("result").isObject()).isTrue();
    }

    @Test
    void listFindingsFiltersAndSortsBySeverity() throws Exception {
        JsonNode all = payload(call("list_findings", "{}"));
        assertThat(all.path("findings").findValuesAsText("severity")).containsExactly("CRITICAL", "MEDIUM");

        JsonNode high = payload(call("list_findings", "{\"min_severity\":\"high\"}"));
        assertThat(high.path("total").asInt()).isEqualTo(1);

        JsonNode ran = payload(call("list_findings", "{\"runtime_only\":true}"));
        assertThat(ran.path("findings").get(0).path("runtimeCalls").asLong()).isEqualTo(99);

        JsonNode bad = call("list_findings", "{\"min_severity\":\"SEVERE\"}");
        assertThat(bad.path("isError").asBoolean()).as("bad argument is a tool error, not a crash").isTrue();
    }

    @Test
    void classInfoAndImpactResolveNames() throws Exception {
        JsonNode info = payload(call("class_info", "{\"name\":\"TeaserModel\"}"));
        assertThat(info.path("id").asText()).isEqualTo("class:com.acme.TeaserModel");
        assertThat(info.path("properties").path("runtime.requests").asText()).isEqualTo("31");
        assertThat(info.path("dependsOn").path("CALLS").get(0).asText()).isEqualTo("class:com.acme.Util");

        JsonNode utilInfo = payload(call("class_info", "{\"name\":\"com.acme.Util\"}"));
        assertThat(utilInfo.path("findings").get(0).path("category").asText()).isEqualTo("ADMIN_SESSION");

        JsonNode ambiguous = call("class_info", "{\"name\":\"Util\"}");
        assertThat(ambiguous.path("isError").asBoolean()).isTrue();
        assertThat(ambiguous.path("content").get(0).path("text").asText()).contains("ambiguous", "org.other.Util");

        JsonNode impact = payload(call("impact", "{\"name\":\"com.acme.Util\"}"));
        assertThat(impact.path("transitiveDependents").asInt()).isEqualTo(2);
        assertThat(impact.path("transitive").get(0).path("id").asText()).as("busiest dependent first")
            .isEqualTo("class:com.acme.TeaserModel");
    }

    @Test
    void summaryAndSearch() throws Exception {
        JsonNode summary = payload(call("summary", "{}"));
        assertThat(summary.path("runtimeSectionsJoined").get(0).asText()).isEqualTo("requests");

        JsonNode search = payload(call("search", "{\"query\":\"servlet\",\"type\":\"SLING_SERVLET\"}"));
        assertThat(search.path("nodes").findValuesAsText("id")).containsExactly("class:com.acme.ExportServlet");
    }

    @Test
    void serveSpeaksNewlineDelimitedJsonRpc() throws Exception {
        String input = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}\n"
            + "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n\n"
            + "{\"jsonrpc\":\"2.0\",\"id\":\"b\",\"method\":\"ping\"}\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        server.serve(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), new PrintStream(out, true, StandardCharsets.UTF_8));

        List<String> lines = out.toString(StandardCharsets.UTF_8).lines().toList();
        assertThat(lines).hasSize(2);
        assertThat(mapper.readTree(lines.get(1)).path("id").asText()).isEqualTo("b");
    }
}
