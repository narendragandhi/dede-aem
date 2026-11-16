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
    private List<RequestLogReader.RequestRecord> serverRequests = List.of();
    /** A method's source lines, for tying a finding to the method it is in. */
    record MethodRange(String classId, String className, String method, int begin, int end) {}
    private Map<Path, List<MethodRange>> methodsByFile = Map.of();
    /** Class -> instrumented methods, from the agent config (--otel-config); empty when unknown. */
    private Map<String, java.util.Set<String>> instrumentedMethods = Map.of();
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

        RuntimeOverlay overlay = new RuntimeOverlay(matched, unmatched, byFile, spans.size(), unattributed,
            windowStart == Long.MAX_VALUE ? 0 : windowStart, windowEnd);
        overlay.methodsByFile = methodRanges(graph);
        overlay.serverRequests = spans.stream().filter(s -> s.httpPath() != null)
            .map(s -> new RequestLogReader.RequestRecord(null, s.httpPath(), s.httpStatus(), s.durationNanos() / 1_000_000))
            .toList();
        return overlay;
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

    private static Map<Path, List<MethodRange>> methodRanges(GraphService graph) {
        Map<Path, List<MethodRange>> byFile = new HashMap<>();
        for (CodeNode node : graph.getAllNodes()) {
            String lines = node.getProperties().get(com.dede.discovery.SourceParser.PROP_LINES);
            if (lines == null || node.getFilePath() == null || !node.getId().startsWith("method:")) continue;
            String signature = node.getSignature();                    // a.b.C.m()
            int dot = signature.lastIndexOf('.', signature.length() - 3);
            if (dot < 0) continue;
            String className = signature.substring(0, dot);
            for (String range : lines.split(",")) {
                String[] parts = range.split("-");
                try {
                    byFile.computeIfAbsent(normalize(node.getFilePath()), p -> new ArrayList<>()).add(new MethodRange(
                        "class:" + className, className, node.getName(), Integer.parseInt(parts[0]), Integer.parseInt(parts[1])));
                } catch (RuntimeException ignored) {
                    // malformed range: no method-level data for it
                }
            }
        }
        return byFile;
    }

    /** The agent config's instrumented methods, so "0 calls" can be claimed for a method. */
    public void setInstrumentedMethods(Map<String, java.util.Set<String>> instrumentedMethods) {
        this.instrumentedMethods = instrumentedMethods;
    }

    /** The method a finding's line is in (innermost), or null for findings outside any method. */
    public String enclosingMethod(Finding finding) {
        MethodRange range = enclosing(finding);
        return range == null ? null : range.method();
    }

    private MethodRange enclosing(Finding finding) {
        if (finding.file() == null || finding.line() <= 0) return null;
        MethodRange best = null;
        for (MethodRange r : methodsByFile.getOrDefault(normalize(finding.file()), List.of())) {
            if (finding.line() >= r.begin() && finding.line() <= r.end()
                    && (best == null || r.end() - r.begin() < best.end() - best.begin())) {
                best = r;
            }
        }
        return best;
    }

    /**
     * Observed calls to the method a finding is in. Null when unknown: the finding is outside a
     * method, its class wasn't observed, or the method has no spans and the agent config (see
     * {@link #setInstrumentedMethods}) doesn't show it was instrumented -- absence of spans from
     * an uninstrumented method says nothing.
     */
    public Long methodCallsFor(Finding finding) {
        MethodRange range = enclosing(finding);
        if (range == null) return null;
        ClassStats stats = matched.get(range.classId());
        if (stats == null) return null;
        Long calls = stats.callsByMethod().get(range.method());
        if (calls != null) return calls;
        return instrumentedMethods.getOrDefault(range.className(), java.util.Set.of()).contains(range.method()) ? 0L : null;
    }

    /**
     * Findings whose code ran during the trace window, most-called first. A finding inside a
     * method is ranked by that method's calls when known, so a finding in a never-called method
     * of a busy class is not "hot".
     */
    public List<Map.Entry<Finding, Long>> hotFindings(List<Finding> findings) {
        List<Map.Entry<Finding, Long>> hot = new ArrayList<>();
        for (Finding f : findings) {
            Long methodCalls = methodCallsFor(f);
            Long calls = methodCalls != null ? methodCalls : callsFor(f);
            if (calls != null && calls > 0) hot.add(Map.entry(f, calls));
        }
        hot.sort(Map.Entry.<Finding, Long>comparingByValue().reversed());
        return hot;
    }

    public List<ClassStats> hotClasses() {
        return matched.values().stream().sorted(Comparator.comparingLong(ClassStats::calls).reversed()).toList();
    }

    /** Length of the trace window in hours (0 when the spans carried no timestamps). */
    public double windowHours() {
        return windowEndNanos > windowStartNanos ? (windowEndNanos - windowStartNanos) / 3.6e12 : 0;
    }

    /**
     * Classes the agent was configured to instrument that produced no span in the window.
     * Unlike "no calls observed" in general, this is evidence: the code was watched and did
     * not run. How strong the evidence is depends on the window -- a 10-minute test run says
     * little about a monthly job.
     */
    public List<Map<String, Object>> instrumentedNotCalled(java.util.Set<String> instrumented, GraphService graph) {
        java.util.Set<String> observed = new java.util.HashSet<>();
        matched.values().forEach(s -> observed.add(s.className()));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String className : instrumented) {
            if (observed.contains(className)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("class", className);
            Optional<CodeNode> node = findClassNode(graph, className);
            if (node.isEmpty()) {
                row.put("verdict", "not in the scanned code (config out of date?)");
            } else {
                boolean entryPoint = OtelConfigEmitter.isEntryPoint(graph, node.get());
                int fanIn = OtelConfigEmitter.fanIn(graph, node.get());
                row.put("type", node.get().getType().name());
                row.put("staticReferences", fanIn);
                row.put("verdict", entryPoint
                    ? "entry point never invoked in the window"
                    : fanIn == 0 ? "likely dead: no static references and no calls"
                    : "referenced but not called in the window");
            }
            rows.add(row);
        }
        return rows;
    }

    /** Human-readable window length: "42 s", "15 min", "3.5 h". */
    public String windowLabel() {
        double hours = windowHours();
        if (hours >= 1) return String.format(java.util.Locale.ROOT, "%.1f h", hours);
        double minutes = hours * 60;
        if (minutes >= 1) return String.format(java.util.Locale.ROOT, "%.0f min", minutes);
        return String.format(java.util.Locale.ROOT, "%.0f s", minutes * 60);
    }

    /** HTTP server spans as requests, for {@link RequestAttribution}. */
    public List<RequestLogReader.RequestRecord> serverRequests() {
        return serverRequests;
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
