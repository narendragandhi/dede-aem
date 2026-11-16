package com.dede.domain;

import com.dede.domain.DeltaAnalyzer.GraphSnapshot;
import com.dede.domain.DeltaAnalyzer.NodeSnapshot;
import com.dede.domain.model.CodeNode;
import com.dede.domain.model.NodeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeltaAnalyzerRuntimeTest {

    @TempDir
    Path dir;

    private static NodeSnapshot node(String id, Map<String, String> runtime) {
        NodeSnapshot n = new NodeSnapshot();
        n.setId(id);
        n.setName(id.substring(id.lastIndexOf('.') + 1));
        n.setType("CLASS");
        n.setSignature(id);
        n.setRuntime(new java.util.TreeMap<>(runtime));
        return n;
    }

    private static GraphSnapshot snapshot(NodeSnapshot... nodes) {
        GraphSnapshot s = new GraphSnapshot();
        s.setTimestamp("t");
        s.getNodes().addAll(java.util.List.of(nodes));
        return s;
    }

    private final DeltaAnalyzer analyzer = new DeltaAnalyzer(null);

    @Test
    void reportsNewErrorsStoppedCodeBigSwingsAndSlowdowns() {
        GraphSnapshot before = snapshot(
            node("class:a.Teaser", Map.of("runtime.calls", "100", "runtime.errors", "0", "runtime.avgMs", "10")),
            node("class:a.Export", Map.of("runtime.calls", "50")),
            node("class:a.Steady", Map.of("runtime.calls", "100")),
            node("class:a.Tiny", Map.of("runtime.calls", "2")));
        GraphSnapshot after = snapshot(
            node("class:a.Teaser", Map.of("runtime.calls", "105", "runtime.errors", "7", "runtime.avgMs", "40")),
            node("class:a.Export", Map.of()),                          // not called any more
            node("class:a.Steady", Map.of("runtime.calls", "130")),    // +30%: noise
            node("class:a.Tiny", Map.of("runtime.calls", "8")));       // 4x but only +6: noise

        var report = analyzer.compare(before, after);

        assertThat(report.getRuntimeChanges())
            .extracting(c -> c.getName() + " " + c.getMetric() + " " + (long) c.getOldValue() + "->" + (long) c.getNewValue())
            .containsExactly(
                "Teaser runtime.errors 0->7",          // new errors first
                "Export runtime.calls 50->0",
                "Teaser runtime.avgMs 10->40");
        assertThat(report.getRuntimeChanges().get(0).isNewErrors()).isTrue();
        assertThat(report.getSeverity()).as("new errors raise severity").isEqualTo("MEDIUM");
        assertThat(analyzer.generateTextReport(report)).contains("RUNTIME CHANGES (3)", "! Teaser errors: 0 → 7");
    }

    @Test
    void metricsOnlyOneScanRecordedAreNotCompared() {
        // The old scan had no --error-log: absence of log errors there means "unknown", not 0.
        GraphSnapshot before = snapshot(node("class:a.Teaser", Map.of("runtime.calls", "100")));
        GraphSnapshot after = snapshot(node("class:a.Teaser", Map.of("runtime.calls", "100", "runtime.logErrors", "12")));

        var report = analyzer.compare(before, after);

        assertThat(report.getRuntimeChanges()).isEmpty();
        assertThat(report.getSeverity()).isEqualTo("NONE");
    }

    @Test
    void snapshotsCarryRuntimePropertiesAndOldSnapshotsStillLoad() throws IOException {
        GraphRepository repo = new GraphRepository();
        GraphService graph = new GraphService(repo, new GraphAnalyzer(repo), new GraphExporter(repo));
        CodeNode cls = new CodeNode("class:a.Teaser", "Teaser", NodeType.CLASS, "a.Teaser", null);
        cls.getProperties().put("runtime.requests", "31");
        cls.getProperties().put("visibility", "public");
        graph.addNode(cls);
        DeltaAnalyzer saved = new DeltaAnalyzer(graph);

        Path file = Path.of(saved.saveSnapshot(dir));
        NodeSnapshot loaded = saved.loadSnapshot(file).getNodes().get(0);
        assertThat(loaded.getRuntime()).containsExactly(Map.entry("runtime.requests", "31"));

        Path old = dir.resolve("old.json");
        Files.writeString(old, """
            {"timestamp":"t","nodes":[{"id":"class:a.Teaser","name":"Teaser","type":"CLASS","signature":"a.Teaser"}],
             "edges":[],"someFutureField":1}
            """);
        assertThat(saved.loadSnapshot(old).getNodes().get(0).getRuntime()).isEmpty();
        assertThat(saved.compareWithSnapshot(old).getRuntimeChanges()).as("old scan had no runtime data").isEmpty();
    }
}
