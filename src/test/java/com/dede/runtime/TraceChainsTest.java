package com.dede.runtime;

import com.dede.runtime.OtlpTraceReader.SpanRecord;
import com.dede.runtime.TraceChains.ErrorChain;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TraceChainsTest {

    private static SpanRecord server(String trace, String id, String path, int status, boolean error) {
        return new SpanRecord(null, null, 1L, 10L, error, path, status, trace, id, null, null, null);
    }

    private static SpanRecord code(String trace, String id, String parent, String cls, String method,
                                   boolean error, String exType, String exMessage) {
        return new SpanRecord(cls, method, 2L, 5L, error, null, 0, trace, id, parent, exType, exMessage);
    }

    /** Request -> servlet -> service; the service throws and the servlet rethrows. */
    private static List<SpanRecord> failingTrace(String trace) {
        return List.of(
            server(trace, "s", "/bin/promo", 500, true),
            code(trace, "a", "s", "com.acme.PromoServlet", "doGet", true, "javax.servlet.ServletException", "wrapped"),
            code(trace, "b", "a", "com.acme.PromoService", "load", true, "java.lang.IllegalStateException", "promo feed down"),
            code(trace, "c", "a", "com.acme.Audit", "log", false, null, null));
    }

    @Test
    void theDeepestFailingCodeSpanIsTheOrigin() {
        List<ErrorChain> chains = TraceChains.from(failingTrace("t1"));

        assertThat(chains).hasSize(1);
        ErrorChain chain = chains.get(0);
        assertThat(chain.route()).isEqualTo("/bin/promo");
        assertThat(chain.httpStatus()).isEqualTo(500);
        assertThat(chain.className()).isEqualTo("com.acme.PromoService");
        assertThat(chain.methodName()).isEqualTo("load");
        assertThat(chain.exceptionType()).isEqualTo("java.lang.IllegalStateException");
        assertThat(chain.exceptionMessage()).isEqualTo("promo feed down");
        assertThat(chain.depth()).isEqualTo(2);
        assertThat(chain.sampleTraceId()).isEqualTo("t1");
    }

    @Test
    void theSameFaultOnManyRequestsIsOneChainWithACount() {
        List<SpanRecord> spans = new ArrayList<>(failingTrace("t1"));
        spans.addAll(failingTrace("t2"));
        spans.addAll(failingTrace("t3"));

        List<ErrorChain> chains = TraceChains.from(spans);

        assertThat(chains).singleElement().satisfies(c -> {
            assertThat(c.count()).isEqualTo(3);
            assertThat(c.sampleTraceId()).isEqualTo("t1");
        });
    }

    @Test
    void successfulRequestsProduceNoChains() {
        List<SpanRecord> spans = List.of(
            server("ok", "s", "/bin/promo", 200, false),
            code("ok", "a", "s", "com.acme.PromoServlet", "doGet", false, null, null));

        assertThat(TraceChains.from(spans)).isEmpty();
    }

    @Test
    void aFailureWithNoCodeSpanBelowItIsUnattributedButKept() {
        List<SpanRecord> spans = List.of(server("t", "s", "/content/x.html", 503, false));

        assertThat(TraceChains.from(spans)).singleElement().satisfies(c -> {
            assertThat(c.attributed()).isFalse();
            assertThat(c.httpStatus()).isEqualTo(503);
        });
    }

    @Test
    void siblingsAtTheSameDepthPreferTheOneThatThrewThenTheEarliest() {
        SpanRecord serverSpan = server("t", "s", "/bin/x", 500, true);
        SpanRecord parent = code("t", "p", "s", "com.acme.Servlet", "doGet", true, null, null);
        SpanRecord lateThrew = new SpanRecord("com.acme.Late", "run", 9L, 1L, true, null, 0, "t", "l", "p",
            "java.io.IOException", "late");
        SpanRecord earlyQuiet = new SpanRecord("com.acme.EarlyQuiet", "run", 3L, 1L, true, null, 0, "t", "e", "p",
            null, null);
        SpanRecord earlyThrew = new SpanRecord("com.acme.Early", "run", 5L, 1L, true, null, 0, "t", "f", "p",
            "java.lang.IllegalStateException", "early");

        // Order in the input must not matter.
        assertThat(TraceChains.from(List.of(serverSpan, parent, lateThrew, earlyQuiet, earlyThrew)))
            .singleElement().satisfies(c -> assertThat(c.className()).isEqualTo("com.acme.Early"));
        assertThat(TraceChains.from(List.of(serverSpan, parent, earlyThrew, earlyQuiet, lateThrew)))
            .singleElement().satisfies(c -> assertThat(c.className()).isEqualTo("com.acme.Early"));
    }

    @Test
    void aHandledErrorInASucceedingBranchIsNeverTheOrigin() {
        List<SpanRecord> spans = List.of(
            server("t", "s", "/bin/promo", 500, true),
            code("t", "a", "s", "com.acme.PromoServlet", "doGet", true, "java.lang.IllegalStateException", "real"),
            code("t", "c", "s", "com.acme.Cache", "get", false, null, null),          // succeeded...
            code("t", "r", "c", "com.acme.RedisClient", "fetch", true, "java.io.IOException", "handled"));

        assertThat(TraceChains.from(spans)).singleElement().satisfies(c -> {
            assertThat(c.className()).isEqualTo("com.acme.PromoServlet");
            assertThat(c.exceptionType()).isEqualTo("java.lang.IllegalStateException");
        });
    }

    @Test
    void theExceptionComesFromTheOriginsOwnPathNotAnotherBranch() {
        List<SpanRecord> spans = List.of(
            server("t", "s", "/bin/x", 500, true),
            code("t", "p", "s", "com.acme.Servlet", "doGet", true, null, null),
            code("t", "a1", "p", "com.acme.AStep", "prepare", true, null, null),
            code("t", "a2", "a1", "com.acme.A", "run", true, null, null),            // deepest, no exception
            code("t", "b", "p", "com.acme.B", "load", true, "java.io.IOException", "other branch"));

        assertThat(TraceChains.from(spans)).singleElement().satisfies(c -> {
            assertThat(c.className()).isEqualTo("com.acme.A");
            assertThat(c.exceptionType()).isNull();
        });
    }

    @Test
    void projectCodeWinsOverADeeperLibrarySpan() {
        List<SpanRecord> spans = List.of(
            server("t", "s", "/bin/promo", 500, true),
            code("t", "a", "s", "com.acme.PromoService", "load", true, "java.lang.RuntimeException", "wrapped"),
            code("t", "b", "a", "org.apache.http.Client", "execute", true, "java.net.SocketTimeoutException", "timeout"));

        assertThat(TraceChains.from(spans, name -> name.startsWith("com.acme."))).singleElement()
            .satisfies(c -> assertThat(c.className()).isEqualTo("com.acme.PromoService"));
        assertThat(TraceChains.from(spans)).singleElement()
            .satisfies(c -> assertThat(c.className()).isEqualTo("org.apache.http.Client"));
    }

    @Test
    void aNestedServiceIsNotEnteredAndTheRequestCountsOnce() {
        List<SpanRecord> spans = List.of(
            server("t", "s", "/content/page.html", 500, true),
            code("t", "a", "s", "com.acme.Model", "init", true, "java.lang.IllegalStateException", "backend down"),
            new SpanRecord(null, null, 1L, 1L, true, "/api/stock", 500, "t", "s2", "a", null, null),
            code("t", "b", "s2", "com.backend.Stock", "get", true, "java.sql.SQLException", "db"));

        assertThat(TraceChains.from(spans)).singleElement().satisfies(c -> {
            assertThat(c.route()).isEqualTo("/content/page.html");
            assertThat(c.className()).isEqualTo("com.acme.Model");
        });
    }

    @Test
    void duplicateExportLinesCountOnce_andStatusesStaySeparate() {
        List<SpanRecord> spans = new ArrayList<>(failingTrace("t1"));
        spans.addAll(failingTrace("t1"));                                           // same batch twice
        spans.add(server("t2", "s", "/bin/promo", 503, true));
        spans.add(code("t2", "a", "s", "com.acme.PromoServlet", "doGet", true, "javax.servlet.ServletException", "wrapped"));
        spans.add(code("t2", "b", "a", "com.acme.PromoService", "load", true, "java.lang.IllegalStateException", "promo feed down"));

        List<ErrorChain> chains = TraceChains.from(spans);

        assertThat(chains).hasSize(2);
        assertThat(chains).allSatisfy(c -> assertThat(c.count()).isEqualTo(1));
        assertThat(chains).extracting(ErrorChain::httpStatus).containsExactlyInAnyOrder(500, 503);
    }

    @Test
    void identicalSiblingsBreakTiesByNameNotInputOrder() {
        SpanRecord serverSpan = server("t", "s", "/bin/x", 500, true);
        SpanRecord z = new SpanRecord("com.acme.Z", "run", 5L, 1L, true, null, 0, "t", "z", "s", "java.io.IOException", "z");
        SpanRecord a = new SpanRecord("com.acme.A", "run", 5L, 1L, true, null, 0, "t", "a", "s", "java.io.IOException", "a");

        assertThat(TraceChains.from(List.of(serverSpan, z, a)).get(0).className()).isEqualTo("com.acme.A");
        assertThat(TraceChains.from(List.of(serverSpan, a, z)).get(0).className()).isEqualTo("com.acme.A");
    }

    @Test
    void spansWithoutTraceIdsAreIgnored() {
        List<SpanRecord> spans = List.of(new SpanRecord(null, null, 1L, 1L, true, "/bin/promo", 500));

        assertThat(TraceChains.from(spans)).isEmpty();
    }

    @Test
    void readsLinkageAndExceptionEventsFromOtlpJson(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        Path file = dir.resolve("trace.json");
        Files.writeString(file, """
            {"resourceSpans":[{"scopeSpans":[{"spans":[
             {"traceId":"5b8e","spanId":"01","parentSpanId":"","name":"GET /bin/promo","kind":"SPAN_KIND_SERVER","startTimeUnixNano":"1","endTimeUnixNano":"9","attributes":[{"key":"url.path","value":{"stringValue":"/bin/promo"}},{"key":"http.response.status_code","value":{"intValue":"500"}}],"status":{"code":"STATUS_CODE_ERROR"}},
             {"traceId":"5b8e","spanId":"02","parentSpanId":"01","name":"PromoService.load","startTimeUnixNano":"2","endTimeUnixNano":"3","attributes":[{"key":"code.namespace","value":{"stringValue":"com.acme.PromoService"}},{"key":"code.function","value":{"stringValue":"load"}}],"status":{"code":2},"events":[{"name":"exception","attributes":[{"key":"exception.type","value":{"stringValue":"java.lang.IllegalStateException"}},{"key":"exception.message","value":{"stringValue":"promo feed down"}}]}]}
            ]}]}]}
            """);

        List<SpanRecord> spans = OtlpTraceReader.read(file);

        assertThat(spans.get(0).parentSpanId()).isNull();
        assertThat(spans.get(1).parentSpanId()).isEqualTo("01");
        assertThat(spans.get(1).exceptionType()).isEqualTo("java.lang.IllegalStateException");
        assertThat(TraceChains.from(spans)).singleElement()
            .satisfies(c -> assertThat(c.className()).isEqualTo("com.acme.PromoService"));
    }
}
