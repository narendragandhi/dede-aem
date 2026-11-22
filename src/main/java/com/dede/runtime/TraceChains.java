package com.dede.runtime;

import com.dede.runtime.OtlpTraceReader.SpanRecord;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Follows each failed request inside its own trace: from the HTTP server span that failed
 * (error status or HTTP 5xx) down to the deepest failing span that is tied to code. That span
 * is where the failure started; the server span is only where it surfaced.
 *
 * <p>Rules that keep the origin honest:
 * <ul>
 *   <li>only failing spans are followed, so the origin sits on an unbroken path of failing
 *       ancestors (an error that a succeeding branch handled is never blamed);</li>
 *   <li>a nested server span (another service in the same trace) is not entered: each service
 *       explains its own failures, and one request is counted once;</li>
 *   <li>project classes win over library or platform spans at any depth, when a predicate
 *       says which classes are the project's;</li>
 *   <li>the exception comes from the origin or its nearest failing ancestor, never from another
 *       branch.</li>
 * </ul>
 *
 * <p>Needs {@code traceId}/{@code spanId}/{@code parentSpanId}; spans without them are skipped.
 * Chains are grouped by route, status, origin class, method and exception type.
 */
public final class TraceChains {

    /** One failure path, aggregated over every trace that took it. */
    public record ErrorChain(String route, int httpStatus, String className, String methodName,
                             String exceptionType, String exceptionMessage, int depth,
                             long count, String sampleTraceId) {
        /** True when an attributed span was found below the server span. */
        public boolean attributed() { return className != null; }
    }

    private TraceChains() {}

    public static List<ErrorChain> from(List<SpanRecord> spans) {
        return from(spans, className -> false);
    }

    /** @param inProject which class names belong to the scanned project (preferred as origins) */
    public static List<ErrorChain> from(List<SpanRecord> spans, Predicate<String> inProject) {
        Map<String, Map<String, SpanRecord>> byTrace = new LinkedHashMap<>();
        for (SpanRecord span : spans) {
            if (span.traceId() != null && span.spanId() != null) {
                // Duplicate export lines repeat a span id: keep the first.
                byTrace.computeIfAbsent(span.traceId(), id -> new LinkedHashMap<>()).putIfAbsent(span.spanId(), span);
            }
        }
        Map<String, ErrorChain> chains = new LinkedHashMap<>();
        for (Map<String, SpanRecord> trace : byTrace.values()) {
            Map<String, List<SpanRecord>> children = new HashMap<>();
            for (SpanRecord span : trace.values()) {
                if (span.parentSpanId() != null) {
                    children.computeIfAbsent(span.parentSpanId(), id -> new ArrayList<>()).add(span);
                }
            }
            for (SpanRecord server : trace.values()) {
                if (!server.isServer() || !(server.error() || server.httpStatus() >= 500)) continue;
                if (hasServerAncestor(server, trace)) continue;   // counted by the outer request
                ErrorChain chain = follow(server, trace, children, inProject);
                String key = String.join("|", chain.route(), String.valueOf(chain.httpStatus()),
                    String.valueOf(chain.className()), String.valueOf(chain.methodName()),
                    String.valueOf(chain.exceptionType()));
                chains.merge(key, chain, (a, b) -> new ErrorChain(a.route(), a.httpStatus(), a.className(),
                    a.methodName(), a.exceptionType(), a.exceptionMessage(), a.depth(), a.count() + 1,
                    a.sampleTraceId()));
            }
        }
        return chains.values().stream()
            .sorted(Comparator.comparingLong(ErrorChain::count).reversed()
                .thenComparing(ErrorChain::route))
            .toList();
    }

    private static boolean hasServerAncestor(SpanRecord span, Map<String, SpanRecord> trace) {
        Set<String> seen = new HashSet<>();
        SpanRecord parent = span.parentSpanId() != null ? trace.get(span.parentSpanId()) : null;
        while (parent != null && seen.add(parent.spanId())) {
            if (parent.isServer()) return true;
            parent = parent.parentSpanId() != null ? trace.get(parent.parentSpanId()) : null;
        }
        return false;
    }

    private record Step(SpanRecord span, int depth) {}

    /** Breadth-first through failing spans below the server span. */
    private static ErrorChain follow(SpanRecord server, Map<String, SpanRecord> trace,
                                     Map<String, List<SpanRecord>> children, Predicate<String> inProject) {
        SpanRecord origin = null;
        int originDepth = 0;
        boolean originInProject = false;
        Deque<Step> queue = new ArrayDeque<>();
        queue.add(new Step(server, 0));
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            Step step = queue.poll();
            SpanRecord span = step.span();
            if (!seen.add(span.spanId())) continue;   // malformed data with a cycle
            if (step.depth() > 0 && span.className() != null) {
                boolean project = inProject.test(span.className());
                if (origin == null || better(span, step.depth(), project, origin, originDepth, originInProject)) {
                    origin = span;
                    originDepth = step.depth();
                    originInProject = project;
                }
            }
            for (SpanRecord child : children.getOrDefault(span.spanId(), List.of())) {
                // Only failures propagate; another service's server span explains itself.
                if (child.error() && !child.isServer()) queue.add(new Step(child, step.depth() + 1));
            }
        }
        SpanRecord cause = exceptionOnPath(origin != null ? origin : server, server, trace);
        return new ErrorChain(server.httpPath(), server.httpStatus(),
            origin != null ? origin.className() : null, origin != null ? origin.methodName() : null,
            cause != null ? cause.exceptionType() : null, cause != null ? cause.exceptionMessage() : null,
            originDepth, 1, server.traceId());
    }

    /** Project code first, then deeper, then the one that threw, then the earliest, then by name. */
    private static boolean better(SpanRecord candidate, int depth, boolean project,
                                  SpanRecord current, int currentDepth, boolean currentProject) {
        if (project != currentProject) return project;
        if (depth != currentDepth) return depth > currentDepth;
        boolean candidateThrew = candidate.exceptionType() != null;
        boolean currentThrew = current.exceptionType() != null;
        if (candidateThrew != currentThrew) return candidateThrew;
        if (candidate.startNanos() != current.startNanos()) return candidate.startNanos() < current.startNanos();
        int byName = (candidate.className() + "#" + candidate.methodName())
            .compareTo(current.className() + "#" + current.methodName());
        return byName != 0 ? byName < 0 : candidate.spanId().compareTo(current.spanId()) < 0;
    }

    /** The exception recorded by {@code from} or its nearest ancestor up to the server span. */
    private static SpanRecord exceptionOnPath(SpanRecord from, SpanRecord server, Map<String, SpanRecord> trace) {
        Set<String> seen = new HashSet<>();
        SpanRecord span = from;
        while (span != null && seen.add(span.spanId())) {
            if (span.exceptionType() != null) return span;
            if (span == server) return null;
            span = span.parentSpanId() != null ? trace.get(span.parentSpanId()) : null;
        }
        return null;
    }
}
