package com.dede.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads OpenTelemetry trace data in OTLP/JSON form. Accepted inputs:
 * <ul>
 *   <li>the OpenTelemetry Collector's {@code file} exporter output: one
 *       {@code {"resourceSpans":[...]}} request per line (or one pretty-printed request);</li>
 *   <li>the Java agent's own log with {@code otel.traces.exporter=logging-otlp}: one bare
 *       {@code ResourceSpans} object per line after a log prefix -- no Collector needed.</li>
 * </ul>
 *
 * <p>Each span is attributed to a Java class and method from its code attributes:
 * <ul>
 *   <li>{@code code.namespace} + {@code code.function} (Java agent "methods" instrumentation
 *       and {@code @WithSpan}, older semantic conventions);</li>
 *   <li>{@code code.function.name} holding {@code fully.qualified.Class.method} (newer
 *       semantic conventions).</li>
 * </ul>
 * Spans without them (HTTP server, JDBC, HTTP client spans) are counted but not attributed:
 * they cannot be tied to a node in the code graph.
 */
public final class OtlpTraceReader {

    /** One span reduced to what the overlay needs. {@code className} is null when unattributed. */
    public record SpanRecord(String className, String methodName, long startNanos, long durationNanos,
                             boolean error) {}

    private OtlpTraceReader() {}

    public static List<SpanRecord> read(Path file) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        List<SpanRecord> spans = new ArrayList<>();
        try (MappingIterator<JsonNode> values = mapper.readerFor(JsonNode.class).readValues(file.toFile())) {
            while (values.hasNextValue()) {
                collect(values.nextValue(), spans);
            }
            return spans;
        } catch (IOException notPureJson) {
            // Not a stream of JSON values: try a log file with OTLP JSON after a prefix on each line.
            List<SpanRecord> fromLog = readLogLines(file, mapper);
            if (fromLog == null) throw notPureJson;
            return fromLog;
        }
    }

    /** Lines that carry OTLP JSON after a log prefix; null when the file has none. */
    private static List<SpanRecord> readLogLines(Path file, ObjectMapper mapper) throws IOException {
        List<SpanRecord> spans = new ArrayList<>();
        boolean found = false;
        for (String line : java.nio.file.Files.readAllLines(file)) {
            if (!(line.contains("\"scopeSpans\"") || line.contains("\"resourceSpans\"")
                || line.contains("\"instrumentationLibrarySpans\""))) {
                continue;
            }
            int brace = line.indexOf('{');
            if (brace < 0) continue;
            collect(mapper.readTree(line.substring(brace)), spans);
            found = true;
        }
        return found ? spans : null;
    }

    /** Accepts an export request ({@code resourceSpans}) or a bare {@code ResourceSpans} object. */
    static void collect(JsonNode value, List<SpanRecord> out) {
        if (value.has("resourceSpans")) {
            for (JsonNode resourceSpans : value.path("resourceSpans")) {
                collectResource(resourceSpans, out);
            }
        } else {
            collectResource(value, out);
        }
    }

    private static void collectResource(JsonNode resourceSpans, List<SpanRecord> out) {
        // "instrumentationLibrarySpans" is the pre-1.0 OTLP name for scopeSpans.
        JsonNode scopes = resourceSpans.has("scopeSpans")
            ? resourceSpans.path("scopeSpans") : resourceSpans.path("instrumentationLibrarySpans");
        for (JsonNode scope : scopes) {
            for (JsonNode span : scope.path("spans")) {
                out.add(toRecord(span));
            }
        }
    }

    private static SpanRecord toRecord(JsonNode span) {
        String namespace = null;
        String function = null;
        String functionName = null;
        for (JsonNode attribute : span.path("attributes")) {
            String key = attribute.path("key").asText();
            String value = attribute.path("value").path("stringValue").asText(null);
            switch (key) {
                case "code.namespace" -> namespace = value;
                case "code.function" -> function = value;
                case "code.function.name" -> functionName = value;
                default -> { }
            }
        }

        String className = null;
        String methodName = null;
        if (namespace != null && !namespace.isBlank()) {
            className = namespace;
            methodName = function;
        } else if (functionName != null && functionName.lastIndexOf('.') > 0) {
            int dot = functionName.lastIndexOf('.');
            className = functionName.substring(0, dot);
            methodName = functionName.substring(dot + 1);
        }

        // OTLP/JSON encodes 64-bit integers as strings; asLong() accepts either form.
        long start = span.path("startTimeUnixNano").asLong(0);
        long end = span.path("endTimeUnixNano").asLong(0);
        JsonNode statusCode = span.path("status").path("code");
        boolean error = statusCode.asInt(-1) == 2 || "STATUS_CODE_ERROR".equals(statusCode.asText());
        return new SpanRecord(className, methodName, start, Math.max(0, end - start), error);
    }
}
