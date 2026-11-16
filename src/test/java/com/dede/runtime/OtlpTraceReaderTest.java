package com.dede.runtime;

import com.dede.runtime.OtlpTraceReader.SpanRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OtlpTraceReaderTest {

    @TempDir
    Path dir;

    /** Two lines, as the Collector's file exporter writes them. */
    private static final String JSON_LINES = """
        {"resourceSpans":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"aem-author"}}]},"scopeSpans":[{"scope":{"name":"io.opentelemetry.methods"},"spans":[{"traceId":"a","spanId":"1","name":"TeaserModel.getTitle","kind":1,"startTimeUnixNano":"1700000000000000000","endTimeUnixNano":"1700000000004000000","attributes":[{"key":"code.namespace","value":{"stringValue":"com.acme.TeaserModel"}},{"key":"code.function","value":{"stringValue":"getTitle"}}],"status":{}}]}]}]}
        {"resourceSpans":[{"resource":{},"scopeSpans":[{"scope":{"name":"io.opentelemetry.tomcat"},"spans":[{"traceId":"b","spanId":"2","name":"GET /content/*","kind":2,"startTimeUnixNano":1700000001000000000,"endTimeUnixNano":1700000001050000000,"attributes":[{"key":"http.route","value":{"stringValue":"/content/*"}}],"status":{"code":2}},{"traceId":"b","spanId":"3","name":"ExportServlet.doGet","startTimeUnixNano":"1700000001001000000","endTimeUnixNano":"1700000001002000000","attributes":[{"key":"code.function.name","value":{"stringValue":"com.acme.ExportServlet.doGet"}}],"status":{"code":"STATUS_CODE_ERROR"}}]}]}]}
        """;

    @Test
    void readsCollectorFileExporterOutput() throws IOException {
        Path file = dir.resolve("traces.json");
        Files.writeString(file, JSON_LINES);

        List<SpanRecord> spans = OtlpTraceReader.read(file);

        assertThat(spans).hasSize(3);
        assertThat(spans.get(0)).isEqualTo(new SpanRecord("com.acme.TeaserModel", "getTitle",
            1_700_000_000_000_000_000L, 4_000_000L, false));
        // HTTP server span: numeric timestamps, error status, but no code attributes.
        assertThat(spans.get(1).className()).isNull();
        assertThat(spans.get(1).durationNanos()).isEqualTo(50_000_000L);
        assertThat(spans.get(1).error()).isTrue();
        // Newer semantic conventions: fully qualified code.function.name.
        assertThat(spans.get(2).className()).isEqualTo("com.acme.ExportServlet");
        assertThat(spans.get(2).methodName()).isEqualTo("doGet");
        assertThat(spans.get(2).error()).isTrue();
    }

    @Test
    void acceptsAPrettyPrintedRequestAndTheLegacyScopeName() throws IOException {
        Path file = dir.resolve("legacy.json");
        Files.writeString(file, """
            {
              "resourceSpans": [{
                "instrumentationLibrarySpans": [{
                  "spans": [{
                    "name": "x",
                    "attributes": [{"key": "code.namespace", "value": {"stringValue": "com.acme.A"}}]
                  }]
                }]
              }]
            }
            """);

        assertThat(OtlpTraceReader.read(file)).extracting(SpanRecord::className).containsExactly("com.acme.A");
    }

    /**
     * Shape the OpenTelemetry Java agent 2.10 really writes with otel.traces.exporter=logging-otlp
     * (trimmed from a captured run): a log prefix, then one bare ResourceSpans object per line.
     */
    @Test
    void readsTheJavaAgentsLoggingOtlpOutputDirectly() throws IOException {
        Path file = dir.resolve("agent.log");
        Files.writeString(file, """
            OpenJDK 64-Bit Server VM warning: Sharing is only supported for boot loader classes
            [otel.javaagent 2026-11-13 18:18:55:590 -0500] [main] INFO io.opentelemetry.javaagent.tooling.VersionLogger - opentelemetry-javaagent - version: 2.10.0
            [otel.javaagent 2026-11-13 18:18:56:759 -0500] [BatchSpanProcessor_WorkerThread-1] INFO io.opentelemetry.exporter.logging.otlp.OtlpJsonLoggingSpanExporter - {"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"aem"}}]},"scopeSpans":[{"scope":{"name":"io.opentelemetry.methods","version":"2.10.0-alpha"},"spans":[{"traceId":"5b1c","spanId":"9a2e","name":"LegacyApprovalStep.execute","kind":1,"startTimeUnixNano":"1763075936651066000","endTimeUnixNano":"1763075936652792000","attributes":[{"key":"code.namespace","value":{"stringValue":"com.acme.LegacyApprovalStep"}},{"key":"code.function","value":{"stringValue":"execute"}},{"key":"thread.id","value":{"intValue":"1"}}],"events":[{"timeUnixNano":"1763075936652700000","name":"exception"}],"status":{"code":2}}]}]}
            """);

        assertThat(OtlpTraceReader.read(file)).singleElement().satisfies(span -> {
            assertThat(span.className()).isEqualTo("com.acme.LegacyApprovalStep");
            assertThat(span.methodName()).isEqualTo("execute");
            assertThat(span.durationNanos()).isEqualTo(1_726_000L);
            assertThat(span.error()).isTrue();
        });
    }

    @Test
    void malformedInputFailsInsteadOfReadingAsEmpty() throws IOException {
        Path file = dir.resolve("broken.json");
        Files.writeString(file, "{\"resourceSpans\": [");

        assertThatThrownBy(() -> OtlpTraceReader.read(file)).isInstanceOf(IOException.class);
    }
}
