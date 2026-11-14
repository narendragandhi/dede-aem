package com.dede.runtime;

import com.dede.cloud.CloudFindings.Finding;
import com.dede.discovery.SourceParser;
import com.dede.domain.GraphAnalyzer;
import com.dede.domain.GraphExporter;
import com.dede.domain.GraphRepository;
import com.dede.domain.GraphService;
import com.dede.domain.model.CodeNode;
import com.dede.domain.model.NodeType;
import com.dede.domain.model.RelationshipType;
import com.dede.runtime.OtlpTraceReader.SpanRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeOverlayTest {

    @TempDir
    Path dir;

    private GraphService graph;
    private Path modelFile;
    private Path servletFile;

    @BeforeEach
    void setUp() throws IOException {
        GraphRepository repo = new GraphRepository();
        graph = new GraphService(repo, new GraphAnalyzer(repo), new GraphExporter(repo));
        SourceParser parser = new SourceParser(graph);

        modelFile = dir.resolve("core/src/main/java/com/acme/TeaserModel.java");
        Files.createDirectories(modelFile.getParent());
        Files.writeString(modelFile, """
            package com.acme;
            public class TeaserModel {
                public String getTitle() { return "t"; }
                public String getLink() { return "l"; }
                class Helper { void help() {} }
            }
            """);
        servletFile = dir.resolve("core/src/main/java/com/acme/ExportServlet.java");
        Files.writeString(servletFile, """
            package com.acme;
            public class ExportServlet {
                public void doGet() {}
            }
            """);
        parser.parse(modelFile);
        parser.parse(servletFile);
    }

    private static SpanRecord span(String cls, String method, long durationMs, boolean error) {
        return new SpanRecord(cls, method, 1_700_000_000_000_000_000L, durationMs * 1_000_000L, error);
    }

    private static Finding finding(Path file, String severity) {
        return new Finding("forbidden-api", "DEPRECATED_WORKFLOW", severity, file.toString(), 3,
            "Legacy workflow API", null, null, null, null);
    }

    @Test
    void aggregatesSpansOntoClassNodes() {
        RuntimeOverlay overlay = RuntimeOverlay.apply(List.of(
            span("com.acme.TeaserModel", "getTitle", 2, false),
            span("com.acme.TeaserModel", "getTitle", 4, false),
            span("com.acme.TeaserModel", "getLink", 6, true),
            span(null, null, 50, false),                              // HTTP server span: no code attributes
            span("org.apache.sling.Other", "x", 1, false)),           // not project code
            graph);

        assertThat(overlay.totalSpans()).isEqualTo(5);
        assertThat(overlay.unattributedSpans()).isEqualTo(1);
        assertThat(overlay.matchedSpans()).isEqualTo(3);
        assertThat(overlay.unmatched()).containsOnlyKeys("org.apache.sling.Other");

        CodeNode model = graph.findNodeById("class:com.acme.TeaserModel").orElseThrow();
        assertThat(model.getProperties())
            .containsEntry(RuntimeOverlay.PROP_CALLS, "3")
            .containsEntry(RuntimeOverlay.PROP_ERRORS, "1")
            .containsEntry(RuntimeOverlay.PROP_AVG_MS, "4.00")
            .containsEntry(RuntimeOverlay.PROP_MAX_MS, "6.00");
        assertThat(overlay.hotClasses().get(0).callsByMethod()).containsEntry("getTitle", 2L).containsEntry("getLink", 1L);
    }

    @Test
    void nestedAndAnonymousClassesRollUpToTheTopLevelClass() {
        RuntimeOverlay overlay = RuntimeOverlay.apply(List.of(
            span("com.acme.TeaserModel$Helper", "help", 1, false),
            span("com.acme.TeaserModel$1", "run", 1, false)), graph);

        assertThat(overlay.unmatched()).isEmpty();
        assertThat(graph.findNodeById("class:com.acme.TeaserModel").orElseThrow().getProperties())
            .containsEntry(RuntimeOverlay.PROP_CALLS, "2");
    }

    @Test
    void ranksFindingsByHowOftenTheirCodeRuns() {
        RuntimeOverlay overlay = RuntimeOverlay.apply(List.of(
            span("com.acme.ExportServlet", "doGet", 1, false),
            span("com.acme.TeaserModel", "getTitle", 1, false),
            span("com.acme.TeaserModel", "getTitle", 1, false)), graph);

        Finding inModel = finding(modelFile, "HIGH");
        Finding inServlet = finding(servletFile, "CRITICAL");
        Finding elsewhere = finding(dir.resolve("core/src/main/java/com/acme/Unused.java"), "CRITICAL");

        assertThat(overlay.hotFindings(List.of(inServlet, elsewhere, inModel)))
            .extracting(Map.Entry::getKey).containsExactly(inModel, inServlet);
        assertThat(overlay.callsFor(inModel)).isEqualTo(2L);
        // Not observed is null, not 0: the class may simply not have been instrumented.
        assertThat(overlay.callsFor(elsewhere)).isNull();
    }

    @Test
    void summaryCarriesWindowHotClassesAndHotFindings() {
        RuntimeOverlay overlay = RuntimeOverlay.apply(List.of(span("com.acme.ExportServlet", "doGet", 3, false)), graph);

        Map<String, Object> summary = overlay.summary(List.of(finding(servletFile, "CRITICAL")), 20);
        assertThat(summary).containsEntry("spans", 1).containsEntry("matchedClasses", 1)
            .containsKeys("windowStart", "windowEnd", "hotClasses", "hotFindings");
        assertThat((List<?>) summary.get("hotFindings")).hasSize(1);
    }

    @Test
    void emitterPrefersFindingsThenEntryPointsAndWritesAgentSyntax() {
        CodeNode servlet = graph.findNodeById("class:com.acme.ExportServlet").orElseThrow();
        servlet.setType(NodeType.SLING_SERVLET);

        List<OtelConfigEmitter.Selection> selections =
            OtelConfigEmitter.select(graph, List.of(finding(modelFile, "HIGH")), 50);

        assertThat(selections).extracting(OtelConfigEmitter.Selection::className)
            .containsExactly("com.acme.TeaserModel", "com.acme.ExportServlet");
        assertThat(selections.get(0).reason()).isEqualTo("has findings");
        assertThat(selections.get(1).reason()).isEqualTo("entry point (sling_servlet)");
        assertThat(OtelConfigEmitter.methodsInclude(selections))
            .isEqualTo("com.acme.TeaserModel[getLink,getTitle];com.acme.ExportServlet[doGet]");
        assertThat(OtelConfigEmitter.render(selections))
            .contains("otel.instrumentation.methods.include=com.acme.TeaserModel[getLink,getTitle];")
            .contains("org.osgi.framework.bootdelegation");
    }

    @Test
    void emitterAddsUnreferencedClassesLastAsDeadCodeSuspectsAndHonorsTheCap() {
        assertThat(OtelConfigEmitter.select(graph, List.of(), 50))
            .extracting(OtelConfigEmitter.Selection::reason)
            .containsOnly(OtelConfigEmitter.REASON_NO_REFERENCES);

        CodeNode servlet = graph.findNodeById("class:com.acme.ExportServlet").orElseThrow();
        servlet.setType(NodeType.SLING_SERVLET);
        CodeNode model = graph.findNodeById("class:com.acme.TeaserModel").orElseThrow();
        model.setType(NodeType.SLING_MODEL);
        assertThat(OtelConfigEmitter.select(graph, List.of(), 1)).hasSize(1);
    }

    @Test
    void fanInCountsCallsIntoDeclaredMethods() {
        CodeNode model = graph.findNodeById("class:com.acme.TeaserModel").orElseThrow();
        CodeNode caller = graph.findNodeById("method:com.acme.ExportServlet.doGet()").orElseThrow();
        CodeNode getTitle = graph.findNodeById("method:com.acme.TeaserModel.getTitle()").orElseThrow();
        graph.addEdge(caller, getTitle, RelationshipType.CALLS);

        assertThat(OtelConfigEmitter.fanIn(graph, model)).isEqualTo(1);
        assertThat(OtelConfigEmitter.select(graph, List.of(), 50))
            .extracting(OtelConfigEmitter.Selection::reason)
            .containsExactly("fan-in 1", OtelConfigEmitter.REASON_NO_REFERENCES);
    }

    @Test
    void readsInstrumentedClassesBackFromAGeneratedConfig() throws IOException {
        CodeNode servlet = graph.findNodeById("class:com.acme.ExportServlet").orElseThrow();
        servlet.setType(NodeType.SLING_SERVLET);
        Path config = dir.resolve("otel.properties");
        Files.writeString(config, OtelConfigEmitter.render(OtelConfigEmitter.select(graph, List.of(), 50)));

        assertThat(OtelConfigEmitter.readInstrumentedClasses(config))
            .containsExactlyInAnyOrder("com.acme.ExportServlet", "com.acme.TeaserModel");
    }

    @Test
    void configWithoutMethodsIncludeIsRejected() throws IOException {
        Path config = dir.resolve("empty.properties");
        Files.writeString(config, "otel.service.name=aem\n");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> OtelConfigEmitter.readInstrumentedClasses(config))
            .isInstanceOf(IOException.class).hasMessageContaining("methods.include");
    }

    @Test
    void instrumentedButNeverCalledGetsAVerdictFromTheGraph() {
        CodeNode servlet = graph.findNodeById("class:com.acme.ExportServlet").orElseThrow();
        servlet.setType(NodeType.SLING_SERVLET);
        RuntimeOverlay overlay = RuntimeOverlay.apply(List.of(span("com.acme.TeaserModel", "getTitle", 1, false)), graph);

        List<Map<String, Object>> rows = overlay.instrumentedNotCalled(
            new java.util.LinkedHashSet<>(List.of("com.acme.TeaserModel", "com.acme.ExportServlet", "com.acme.Gone")), graph);

        assertThat(rows).extracting(r -> r.get("class"), r -> r.get("verdict")).containsExactly(
            org.assertj.core.groups.Tuple.tuple("com.acme.ExportServlet", "entry point never invoked in the window"),
            org.assertj.core.groups.Tuple.tuple("com.acme.Gone", "not in the scanned code (config out of date?)"));

        CodeNode helper = new CodeNode("class:com.acme.Helper", "Helper", NodeType.CLASS, "com.acme.Helper", "Helper.java");
        graph.addNode(helper);
        assertThat(overlay.instrumentedNotCalled(java.util.Set.of("com.acme.Helper"), graph))
            .singleElement().extracting(r -> r.get("verdict"))
            .isEqualTo("likely dead: no static references and no calls");
    }

    @Test
    void windowLabelScalesToTheWindow() {
        RuntimeOverlay seconds = RuntimeOverlay.apply(List.of(
            new SpanRecord("com.acme.TeaserModel", "getTitle", 1_000_000_000_000L, 42_000_000_000L, false)), graph);
        assertThat(seconds.windowLabel()).isEqualTo("42 s");
    }
}
