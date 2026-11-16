package com.dede.cli;

import com.dede.cloud.CloudFindings;
import com.dede.domain.GraphService;
import com.dede.runtime.ContentIndex;
import com.dede.runtime.ContentSource;
import com.dede.runtime.ErrorAttribution;
import com.dede.runtime.ErrorLogReader;
import com.dede.runtime.OtelConfigEmitter;
import com.dede.runtime.OtlpTraceReader;
import com.dede.runtime.RequestAttribution;
import com.dede.runtime.RequestLogReader;
import com.dede.runtime.RuntimeOverlay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The runtime stage of a scan: joins traces, request logs, the site's content and error.log onto
 * the graph. Everything after it -- snapshots, blast radius, reports, MCP -- reads the
 * {@code runtime.*} metrics this writes, so it must run first; {@link RuntimeData} is the proof
 * that it has.
 */
public final class RuntimeJoin {

    private static final Logger log = LoggerFactory.getLogger(RuntimeJoin.class);

    /** What the runtime stage produced; each part is null when its input wasn't given. */
    public record RuntimeData(RuntimeOverlay traces,
                              List<Map<String, Object>> instrumentedNotCalled,
                              RequestAttribution requests,
                              ErrorAttribution errors,
                              ContentIndex content) {
        public boolean hasTraffic() { return traces != null || requests != null; }
    }

    /** A runtime input that couldn't be read: the scan stops rather than report without it. */
    public static final class StageFailure extends Exception {
        public StageFailure(String message) { super(message); }
    }

    private RuntimeJoin() {}

    public static RuntimeData run(CliOptions o, CloudFindings findings, GraphService graph) throws StageFailure {
        RuntimeOverlay traces = null;
        List<Map<String, Object>> instrumentedNotCalled = null;
        if (o.runtimeTracesPath() != null) {
            try {
                traces = RuntimeOverlay.apply(OtlpTraceReader.read(Path.of(o.runtimeTracesPath())), graph);
                if (o.instrumentedConfigPath() != null) {
                    var instrumented = OtelConfigEmitter.readInstrumentedMethods(Path.of(o.instrumentedConfigPath()));
                    traces.setInstrumentedMethods(instrumented);
                    instrumentedNotCalled = traces.instrumentedNotCalled(instrumented.keySet(), graph);
                }
            } catch (IOException | RuntimeException e) {
                throw new StageFailure("Failed to read runtime traces from " + o.runtimeTracesPath() + ": " + e.getMessage());
            }
            logTraces(traces, instrumentedNotCalled, findings);
        }

        ContentIndex[] content = new ContentIndex[1];
        RequestAttribution requests = attributeRequests(o, traces, graph, content);
        if (content[0] == null && !o.blastRadius().isEmpty()) {
            content[0] = loadContent(o);   // the blast radius' page layer needs pages even without requests
        }
        ErrorAttribution errors = null;
        if (o.errorLogPath() != null) {
            try {
                errors = ErrorAttribution.apply(ErrorLogReader.read(Path.of(o.errorLogPath())), graph);
            } catch (IOException e) {
                throw new StageFailure("Failed to read error log " + o.errorLogPath() + ": " + e.getMessage());
            }
            logErrors(errors, findings);
        }
        return new RuntimeData(traces, instrumentedNotCalled, requests, errors, content[0]);
    }

    /** HTTP server spans and/or AEM request.log/access.log -> project code. */
    private static RequestAttribution attributeRequests(CliOptions o, RuntimeOverlay traces, GraphService graph,
                                                        ContentIndex[] contentOut) throws StageFailure {
        List<RequestLogReader.RequestRecord> records = new ArrayList<>();
        if (traces != null && o.requestLogPath() == null) {
            records.addAll(traces.serverRequests());
        } else if (traces != null && !traces.serverRequests().isEmpty()) {
            // Both describe the same traffic; counting both doubles every request.
            log.info("Requests: using --request-log; ignoring {} HTTP server span(s) from the traces "
                + "(same requests, would be counted twice).", traces.serverRequests().size());
        }
        if (o.requestLogPath() != null) {
            try {
                records.addAll(RequestLogReader.read(Path.of(o.requestLogPath())));
            } catch (IOException e) {
                throw new StageFailure("Failed to read request log " + o.requestLogPath() + ": " + e.getMessage());
            }
        }
        if (records.isEmpty()) {
            if (!o.contentSources().isEmpty() && o.blastRadius().isEmpty()) {
                log.warn("--content is only used to attribute requests: add --request-log (or traces with HTTP server spans).");
            }
            return null;
        }
        ContentIndex content = loadContent(o);
        contentOut[0] = content;
        RequestAttribution requests = RequestAttribution.apply(records, content, graph);
        log.info("Requests: {} total, {} attributed to project code ({} content page(s) indexed)",
            requests.total(), requests.attributed(), content.pageCount());
        requests.hotClasses(10).forEach(e -> log.info("  {} requests  {}", e.getValue(), e.getKey()));
        log.info("  (counts requests that reached AEM: on publish, dispatcher/CDN cache hits are not included)");
        List<String> neverRendered = requests.resourceTypesNeverRendered();
        if (!neverRendered.isEmpty()) {
            log.info("Resource types with project models/servlets never rendered: {}", neverRendered.size());
        }
        return requests;
    }

    /** The project's content plus every --content source. */
    private static ContentIndex loadContent(CliOptions o) throws StageFailure {
        ContentIndex content = ContentIndex.build(Path.of(o.projectPath()));
        for (String source : o.contentSources()) {
            try {
                log.info("Content: {} node(s)/document(s) added from {}", ContentSource.addTo(content, source), source);
            } catch (IOException | RuntimeException e) {
                // Attribution without the site's pages silently undercounts: stop instead.
                throw new StageFailure("Failed to read content from " + source + ": " + e.getMessage());
            }
        }
        return content;
    }

    private static void logTraces(RuntimeOverlay traces, List<Map<String, Object>> instrumentedNotCalled,
                                  CloudFindings findings) {
        log.info("Runtime traces: {} span(s), {} matched to {} project class(es), {} without code attributes, "
                + "{} class(es) outside the scanned code",
            traces.totalSpans(), traces.matchedSpans(), traces.matchedClassCount(), traces.unattributedSpans(),
            traces.unmatched().size());
        if (traces.matchedClassCount() == 0) {
            String hint = "no method span could be tied to project code. Method spans need code.namespace/"
                + "code.function attributes -- generate an agent config with --emit-otel-config.";
            if (traces.serverRequests().isEmpty()) {
                log.warn("Runtime traces: {}", hint);
            } else {
                log.info("Runtime traces: {} HTTP request span(s) will be attributed by path; {}",
                    traces.serverRequests().size(), hint);
            }
        }
        if (instrumentedNotCalled != null) {
            log.info("Instrumented but never called in a {} trace window: {} class(es)",
                traces.windowLabel(), instrumentedNotCalled.size());
            instrumentedNotCalled.stream().limit(10).forEach(r -> log.info("  {} -- {}", r.get("class"), r.get("verdict")));
            if (traces.windowHours() < 24) {
                log.warn("Trace window is under 24 hours: 'never called' only covers that window "
                    + "(scheduled and monthly jobs may simply not have run).");
            }
        }
        var hot = traces.hotFindings(findings.all());
        if (!hot.isEmpty()) {
            log.info("Findings in code that ran ({}), most-called first:", hot.size());
            hot.stream().limit(10).forEach(e -> log.info("  {} calls  {} [{}] {}", e.getValue(),
                e.getKey().severity(), e.getKey().category(), e.getKey().file()));
        }
    }

    private static void logErrors(ErrorAttribution errors, CloudFindings findings) {
        log.info("Error log: {} WARN/ERROR entries ({} ERROR), {} attributed to {} project class(es)",
            errors.total(), errors.errorCount(), errors.attributed(), errors.failingClasses().size());
        errors.failingClasses().stream().limit(10).forEach(c ->
            log.info("  {} errors, {} warnings  {}", c.errors(), c.warnings(), c.className()));
        var failing = errors.findingsInFailingCode(findings.all());
        if (!failing.isEmpty()) {
            log.info("Findings in code that logged errors ({}):", failing.size());
            failing.stream().limit(10).forEach(e -> log.info("  {} log entries  {} [{}] {}", e.getValue(),
                e.getKey().severity(), e.getKey().category(), e.getKey().file()));
        }
    }
}
