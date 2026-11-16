package com.dede.domain.model;

import java.util.List;
import java.util.Locale;

/**
 * The runtime metrics dede writes onto graph nodes (as {@code runtime.*} properties, so they show
 * in the REST, GraphQL and snapshot output) and the one place that names them. Writers
 * ({@code com.dede.runtime}) and readers (blast radius, snapshots, MCP) all go through here, so a
 * metric can't be renamed in one and silently missed in another.
 */
public final class RuntimeMetrics {

    public static final String PREFIX = "runtime.";

    /** Spans observed for a class (OpenTelemetry method instrumentation). */
    public static final String CALLS = "runtime.calls";
    /** Spans that ended in error. */
    public static final String ERRORS = "runtime.errors";
    public static final String AVG_MS = "runtime.avgMs";
    public static final String MAX_MS = "runtime.maxMs";
    /** Requests attributed to a class (servlet, or model on the rendered page). */
    public static final String REQUESTS = "runtime.requests";
    /** On resource-type nodes: estimated renders of the component. */
    public static final String RENDERS = "runtime.renders";
    /** WARN/ERROR error.log entries attributed to a class. */
    public static final String LOG_ERRORS = "runtime.logErrors";
    public static final String LOG_WARNINGS = "runtime.logWarnings";

    /** Counters (as opposed to latencies). */
    public static final List<String> COUNTS = List.of(CALLS, REQUESTS, RENDERS, ERRORS, LOG_ERRORS, LOG_WARNINGS);
    /** Counters that mean something failed. */
    public static final List<String> FAILURES = List.of(ERRORS, LOG_ERRORS);
    /** Traffic at a node, most direct measure first. */
    private static final List<String> TRAFFIC = List.of(REQUESTS, RENDERS, CALLS);

    private RuntimeMetrics() {}

    public static void set(CodeNode node, String metric, long value) {
        node.getProperties().put(metric, Long.toString(value));
    }

    public static void set(CodeNode node, String metric, double value) {
        node.getProperties().put(metric, String.format(Locale.ROOT, "%.2f", value));
    }

    /** The metric's value, or null when the node doesn't have it (or it isn't a number). */
    public static Double get(CodeNode node, String metric) {
        return parse(node.getProperties().get(metric));
    }

    public static Double parse(String raw) {
        if (raw == null) return null;
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Observed traffic at a node: requests, else renders, else calls -- the first one recorded.
     * Null when no runtime data reached the node.
     */
    public static Long traffic(CodeNode node) {
        if (node == null) return null;
        for (String metric : TRAFFIC) {
            Double value = get(node, metric);
            if (value != null) return value.longValue();
        }
        return null;
    }
}
