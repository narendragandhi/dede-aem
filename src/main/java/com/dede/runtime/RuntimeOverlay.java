package com.dede.runtime;

import com.dede.cloud.CloudFindings.Finding;
import com.dede.domain.GraphService;
import com.dede.domain.model.CodeNode;
import com.dede.runtime.OtlpTraceReader.SpanRecord;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Joins runtime traces to the static code graph: per-class call counts, errors and latency,
 * written onto the graph's class nodes (properties {@code runtime.*}, visible in the graph
 * JSON and GraphQL) and used to rank findings by how often the affected code actually runs.
 *
 * <p>Absence of calls is not evidence of dead code unless the class was instrumented for the
 * whole trace window -- see {@link OtelConfigEmitter}.
 */
public final class RuntimeOverlay {

    public static final String PROP_CALLS = "runtime.calls";
    public static final String PROP_ERRORS = "runtime.errors";
    public static final String PROP_AVG_MS = "runtime.avgMs";
    public static final String PROP_MAX_MS = "runtime.maxMs";

    /** Aggregated runtime behavior of one class. */
    public static final class ClassStats {
        private final String className;
        private long calls;
        private long errors;
        private long totalNanos;
        private long maxNanos;
        private final Map<String, Long> callsByMethod = new TreeMap<>();

        ClassStats(String className) {
            this.className = className;
        }

        void add(SpanRecord span) {
            calls++;
            if (span.error()) errors++;
            totalNanos += span.durationNanos();
            maxNanos = Math.max(maxNanos, span.durationNanos());
            if (span.methodName() != null) callsByMethod.merge(span.methodName(), 1L, Long::sum);
        }

        public String className() { return className; }
        public long calls() { return calls; }
        public long errors() { return errors; }
        public double avgMs() { return calls == 0 ? 0 : totalNanos / 1e6 / calls; }
        public double maxMs() { return maxNanos / 1e6; }
        public Map<String, Long> callsByMethod() { return callsByMethod; }
    }

    private final Map<String, ClassStats> matched;      // graph node id -> stats
    private final Map<String, ClassStats> unmatched;    // class name -> stats (not in the scanned code)
    private final Map<Path, List<String>> nodeIdsByFile;
    private final int totalSpans;
    private final int unattributedSpans;
    private final long windowStartNanos;
    private final long windowEndNanos;

    private RuntimeOverlay(Map<String, ClassStats> matched, Map<String, ClassStats> unmatched,
                           Map<Path, List<String>> nodeIdsByFile, int totalSpans, int unattributedSpans,
                           long windowStartNanos, long windowEndNanos) {
        this.matched = matched;
        this.unmatched = unmatched;
        this.nodeIdsByFile = nodeIdsByFile;
        this.totalSpans = totalSpans;
        this.unattributedSpans = unattributedSpans;
        this.windowStartNanos = windowStartNanos;
        this.windowEndNanos = windowEndNanos;
    }

    /** Aggregates {@code spans}, matches them to class nodes and annotates those nodes. */
    public static RuntimeOverlay apply(List<SpanRecord> spans, GraphService graph) {
        Map<String, ClassStats> matched = new LinkedHashMap<>();
        Map<String, ClassStats> unmatched = new TreeMap<>();
        int unattributed = 0;
        long windowStart = Long.MAX_VALUE;
        long windowEnd = 0;

        for (SpanRecord span : spans) {
            if (span.startNanos() > 0) {
                windowStart = Math.min(windowStart, span.startNanos());
                windowEnd = Math.max(windowEnd, span.startNanos() + span.durationNanos());
            }
            if (span.className() == null) {
                unattributed++;
                continue;
            }
            Optional<CodeNode> node = findClassNode(graph, span.className());
            if (node.isPresent()) {
                matched.computeIfAbsent(node.get().getId(), id -> new ClassStats(node.get().getSignature())).add(span);
            } else {
                unmatched.computeIfAbsent(span.className(), ClassStats::new).add(span);
            }
        }

        for (Map.Entry<String, ClassStats> entry : matched.entrySet()) {
            graph.findNodeById(entry.getKey()).ifPresent(node -> {
                ClassStats stats = entry.getValue();
                node.getProperties().put(PROP_CALLS, Long.toString(stats.calls()));
                node.getProperties().put(PROP_ERRORS, Long.toString(stats.errors()));
                node.getProperties().put(PROP_AVG_MS, String.format(java.util.Locale.ROOT, "%.2f", stats.avgMs()));
                node.getProperties().put(PROP_MAX_MS, String.format(java.util.Locale.ROOT, "%.2f", stats.maxMs()));
            });
        }

        Map<Path, List<String>> byFile = new HashMap<>();
        for (CodeNode node : graph.getAllNodes()) {
            if (node.getId().startsWith("class:") && node.getFilePath() != null) {
                byFile.computeIfAbsent(normalize(node.getFilePath()), p -> new ArrayList<>()).add(node.getId());
            }
        }

        return new RuntimeOverlay(matched, unmatched, byFile, spans.size(), unattributed,
            windowStart == Long.MAX_VALUE ? 0 : windowStart, windowEnd);
    }

    /**
     * Graph class node for a runtime class name. Nested and anonymous classes
     * ({@code Outer$Inner}, {@code Outer$1}) roll up to the top-level class, which is the
     * granularity the graph and the findings use.
     */
    static Optional<CodeNode> findClassNode(GraphService graph, String className) {
        Optional<CodeNode> exact = graph.findNodeById("class:" + className);
        if (exact.isPresent()) return exact;
        int dollar = className.indexOf('$');
        return dollar > 0 ? graph.findNodeById("class:" + className.substring(0, dollar)) : Optional.empty();
    }

    private static Path normalize(String file) {
        return Path.of(file).toAbsolutePath().normalize();
    }

    /** Observed calls to the code in {@code finding}'s file, or null when none were observed. */
    public Long callsFor(Finding finding) {
        if (finding.file() == null) return null;
        List<String> ids = nodeIdsByFile.get(normalize(finding.file()));
        if (ids == null) return null;
        long calls = 0;
        boolean seen = false;
        for (String id : ids) {
            ClassStats stats = matched.get(id);
            if (stats != null) {
                calls += stats.calls();
                seen = true;
            }
        }
        return seen ? calls : null;
    }

    /** Findings whose code ran during the trace window, most-called first. */
    public List<Map.Entry<Finding, Long>> hotFindings(List<Finding> findings) {
        List<Map.Entry<Finding, Long>> hot = new ArrayList<>();
        for (Finding f : findings) {
            Long calls = callsFor(f);
            if (calls != null && calls > 0) hot.add(Map.entry(f, calls));
        }
        hot.sort(Map.Entry.<Finding, Long>comparingByValue().reversed());
        return hot;
    }

    public List<ClassStats> hotClasses() {
        return matched.values().stream().sorted(Comparator.comparingLong(ClassStats::calls).reversed()).toList();
    }

    public int totalSpans() { return totalSpans; }
    public int unattributedSpans() { return unattributedSpans; }
    /** Spans tied to a class in the scanned project. */
    public long matchedSpans() { return matched.values().stream().mapToLong(ClassStats::calls).sum(); }
    public int matchedClassCount() { return matched.size(); }
    public Map<String, ClassStats> unmatched() { return unmatched; }

    /** The "runtime" block of the JSON report. */
    public Map<String, Object> summary(List<Finding> findings, int limit) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("spans", totalSpans);
        summary.put("unattributedSpans", unattributedSpans);
        summary.put("matchedClasses", matched.size());
        summary.put("unmatchedClasses", unmatched.size());
        if (windowEndNanos > 0) {
            summary.put("windowStart", java.time.Instant.ofEpochSecond(0, windowStartNanos).toString());
            summary.put("windowEnd", java.time.Instant.ofEpochSecond(0, windowEndNanos).toString());
        }
        summary.put("hotClasses", hotClasses().stream().limit(limit).map(s -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("class", s.className());
            row.put("calls", s.calls());
            row.put("errors", s.errors());
            row.put("avgMs", Math.round(s.avgMs() * 100) / 100.0);
            row.put("maxMs", Math.round(s.maxMs() * 100) / 100.0);
            row.put("methods", s.callsByMethod());
            return row;
        }).toList());
        summary.put("hotFindings", hotFindings(findings).stream().limit(limit).map(e -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("severity", e.getKey().severity());
            row.put("category", e.getKey().category());
            row.put("file", e.getKey().file());
            row.put("line", e.getKey().line());
            row.put("runtimeCalls", e.getValue());
            return row;
        }).toList());
        return summary;
    }
}
