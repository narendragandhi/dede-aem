package com.dede.runtime;

import com.dede.cloud.CloudFindings.Finding;
import com.dede.domain.GraphService;
import com.dede.domain.model.CodeNode;
import com.dede.runtime.ErrorLogReader.LogEntry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Ties error.log WARN/ERROR entries to project classes and to the findings in them.
 *
 * <p>An entry belongs to the topmost stack frame in project code -- where the failure passed
 * through the project, even when a platform class logged it (Sling logging a model that threw).
 * Without a project frame it belongs to its logger, when the logger is a project class. Others
 * are platform noise and are only counted.
 *
 * <p>Per class this writes {@code runtime.logErrors} / {@code runtime.logWarnings} onto the
 * graph node.
 */
public final class ErrorAttribution {

    public static final String PROP_LOG_ERRORS = "runtime.logErrors";
    public static final String PROP_LOG_WARNINGS = "runtime.logWarnings";

    /** Digits, hex ids and UUIDs vary per occurrence; strip them so repeats group together. */
    private static final Pattern VOLATILE = Pattern.compile(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|\\b0x[0-9a-fA-F]+\\b|\\d+");
    private static final int MESSAGE_LIMIT = 200;

    public static final class ClassErrors {
        private final String className;
        private long errors;
        private long warnings;
        private String firstSeen;
        private String lastSeen;
        private final Map<String, Long> bySignature = new LinkedHashMap<>();
        private final Map<String, String> sampleBySignature = new HashMap<>();
        private final Map<String, Long> byRequestPath = new TreeMap<>();
        private final Map<String, Long> byAttribution = new TreeMap<>();

        ClassErrors(String className) { this.className = className; }

        void add(LogEntry e, String via) {
            if ("ERROR".equals(e.level())) errors++; else warnings++;
            if (firstSeen == null) firstSeen = e.timestamp();
            lastSeen = e.timestamp();
            String signature = signature(e);
            bySignature.merge(signature, 1L, Long::sum);
            sampleBySignature.putIfAbsent(signature, truncate(e.message()));
            if (e.requestPath() != null) byRequestPath.merge(e.requestPath(), 1L, Long::sum);
            byAttribution.merge(via, 1L, Long::sum);
        }

        public String className() { return className; }
        public long errors() { return errors; }
        public long warnings() { return warnings; }
        public long total() { return errors + warnings; }
    }

    private final Map<String, ClassErrors> byClassId;           // graph node id -> stats
    private final Map<Path, List<String>> nodeIdsByFile;
    private final Map<String, Long> unattributedByLogger;
    private final Map<String, Long> exceptionTypes;
    private final int total;
    private final long errorCount;
    private final String windowStart;
    private final String windowEnd;

    private ErrorAttribution(Map<String, ClassErrors> byClassId, Map<Path, List<String>> nodeIdsByFile,
                             Map<String, Long> unattributedByLogger, Map<String, Long> exceptionTypes,
                             int total, long errorCount, String windowStart, String windowEnd) {
        this.byClassId = byClassId;
        this.nodeIdsByFile = nodeIdsByFile;
        this.unattributedByLogger = unattributedByLogger;
        this.exceptionTypes = exceptionTypes;
        this.total = total;
        this.errorCount = errorCount;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
    }

    public static ErrorAttribution apply(List<LogEntry> entries, GraphService graph) {
        Map<String, ClassErrors> byClassId = new LinkedHashMap<>();
        Map<String, Long> unattributed = new HashMap<>();
        Map<String, Long> exceptionTypes = new HashMap<>();
        Map<String, Optional<CodeNode>> resolved = new HashMap<>();   // same classes repeat constantly
        long errorCount = 0;

        for (LogEntry e : entries) {
            if ("ERROR".equals(e.level())) errorCount++;
            if (e.exceptionType() != null) exceptionTypes.merge(e.exceptionType(), 1L, Long::sum);
            CodeNode owner = null;
            String via = null;
            for (String frame : e.frames()) {
                Optional<CodeNode> node = resolved.computeIfAbsent(frame, c -> RuntimeOverlay.findClassNode(graph, c));
                if (node.isPresent()) {
                    owner = node.get();
                    via = "stack";
                    break;
                }
            }
            if (owner == null) {
                Optional<CodeNode> node = resolved.computeIfAbsent(e.logger(), c -> RuntimeOverlay.findClassNode(graph, c));
                if (node.isPresent()) {
                    owner = node.get();
                    via = "logger";
                }
            }
            if (owner == null) {
                unattributed.merge(e.logger(), 1L, Long::sum);
                continue;
            }
            CodeNode node = owner;
            byClassId.computeIfAbsent(node.getId(), id -> new ClassErrors(node.getSignature())).add(e, via);
        }

        for (Map.Entry<String, ClassErrors> entry : byClassId.entrySet()) {
            graph.findNodeById(entry.getKey()).ifPresent(node -> {
                node.getProperties().put(PROP_LOG_ERRORS, Long.toString(entry.getValue().errors()));
                node.getProperties().put(PROP_LOG_WARNINGS, Long.toString(entry.getValue().warnings()));
            });
        }

        Map<Path, List<String>> byFile = new HashMap<>();
        for (CodeNode node : graph.getAllNodes()) {
            if (node.getId().startsWith("class:") && node.getFilePath() != null) {
                byFile.computeIfAbsent(normalize(node.getFilePath()), p -> new ArrayList<>()).add(node.getId());
            }
        }
        String start = entries.isEmpty() ? null : entries.get(0).timestamp();
        String end = entries.isEmpty() ? null : entries.get(entries.size() - 1).timestamp();
        return new ErrorAttribution(byClassId, byFile, unattributed, exceptionTypes, entries.size(), errorCount,
            start, end);
    }

    static String signature(LogEntry e) {
        String message = VOLATILE.matcher(truncate(e.message())).replaceAll("#");
        return e.exceptionType() != null ? e.exceptionType() + ": " + message : message;
    }

    private static String truncate(String s) {
        return s.length() > MESSAGE_LIMIT ? s.substring(0, MESSAGE_LIMIT) + "..." : s;
    }

    private static Path normalize(String file) {
        return Path.of(file).toAbsolutePath().normalize();
    }

    /** WARN+ERROR entries logged by the code in {@code finding}'s file, or null when none. */
    public Long entriesFor(Finding finding) {
        if (finding.file() == null) return null;
        List<String> ids = nodeIdsByFile.get(normalize(finding.file()));
        if (ids == null) return null;
        long count = 0;
        boolean seen = false;
        for (String id : ids) {
            ClassErrors stats = byClassId.get(id);
            if (stats != null) {
                count += stats.total();
                seen = true;
            }
        }
        return seen ? count : null;
    }

    /** Findings in classes that logged WARN/ERROR entries, most entries first. */
    public List<Map.Entry<Finding, Long>> findingsInFailingCode(List<Finding> findings) {
        List<Map.Entry<Finding, Long>> out = new ArrayList<>();
        for (Finding f : findings) {
            Long n = entriesFor(f);
            if (n != null && n > 0) out.add(Map.entry(f, n));
        }
        out.sort(Map.Entry.<Finding, Long>comparingByValue().reversed());
        return out;
    }

    public List<ClassErrors> failingClasses() {
        return byClassId.values().stream()
            .sorted(Comparator.comparingLong(ClassErrors::errors).thenComparingLong(ClassErrors::warnings).reversed())
            .toList();
    }

    public int total() { return total; }
    public long errorCount() { return errorCount; }
    public long attributed() { return byClassId.values().stream().mapToLong(ClassErrors::total).sum(); }

    /** The "logs" block of the JSON report. */
    public Map<String, Object> summary(List<Finding> findings, int limit) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("entries", total);
        summary.put("errors", errorCount);
        summary.put("warnings", total - errorCount);
        summary.put("attributed", attributed());
        if (windowStart != null) {
            summary.put("windowStart", windowStart);
            summary.put("windowEnd", windowEnd);
        }
        summary.put("byClass", failingClasses().stream().limit(limit).map(c -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("class", c.className());
            row.put("errors", c.errors());
            row.put("warnings", c.warnings());
            row.put("firstSeen", c.firstSeen);
            row.put("lastSeen", c.lastSeen);
            row.put("attributedVia", c.byAttribution);
            row.put("signatures", top(c.bySignature, 5).stream().map(s -> {
                Map<String, Object> sig = new LinkedHashMap<>();
                sig.put("signature", s.getKey());
                sig.put("count", s.getValue());
                sig.put("sample", c.sampleBySignature.get(s.getKey()));
                return sig;
            }).toList());
            if (!c.byRequestPath.isEmpty()) row.put("requestPaths", toMap(top(c.byRequestPath, 5)));
            return row;
        }).toList());
        summary.put("findingsInFailingCode", findingsInFailingCode(findings).stream().limit(limit).map(e -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("severity", e.getKey().severity());
            row.put("category", e.getKey().category());
            row.put("file", e.getKey().file());
            row.put("line", e.getKey().line());
            row.put("logEntries", e.getValue());
            return row;
        }).toList());
        summary.put("topExceptionTypes", toMap(top(exceptionTypes, 10)));
        summary.put("topUnattributedLoggers", toMap(top(unattributedByLogger, 10)));
        return summary;
    }

    private static List<Map.Entry<String, Long>> top(Map<String, Long> counts, int limit) {
        return counts.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
            .limit(limit).toList();
    }

    private static Map<String, Long> toMap(List<Map.Entry<String, Long>> entries) {
        Map<String, Long> map = new LinkedHashMap<>();
        entries.forEach(e -> map.put(e.getKey(), e.getValue()));
        return map;
    }
}
