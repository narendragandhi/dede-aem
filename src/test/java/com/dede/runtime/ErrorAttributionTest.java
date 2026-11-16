package com.dede.runtime;

import com.dede.cloud.CloudFindings.Finding;
import com.dede.discovery.SourceParser;
import com.dede.domain.GraphAnalyzer;
import com.dede.domain.GraphExporter;
import com.dede.domain.GraphRepository;
import com.dede.domain.GraphService;
import com.dede.runtime.ErrorLogReader.LogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ErrorAttributionTest {

    @TempDir
    Path dir;

    private GraphService graph;
    private Path modelFile;

    // Shapes copied from a real AEM SDK error.log (request thread, multi-cause stack trace).
    private static final String LOG = """
        14.11.2026 08:52:56.138 *INFO* [FelixLogListener] Events.Service.foo Service REGISTERED
        14.11.2026 17:07:20.764 *ERROR* [[0:0:0:0:0:0:0:1] [1794694040618] GET /content/acme/en.html?x=1 HTTP/1.1] org.apache.sling.models.impl.ModelAdapterFactory Could not adapt to model
        org.apache.sling.models.factory.ModelClassException: Could not invoke @PostConstruct
        \tat org.apache.sling.models.impl.ModelAdapterFactory.createObject(ModelAdapterFactory.java:731) [org.apache.sling.models.impl:1.5.4]
        Caused by: java.lang.NullPointerException: Cannot invoke "String.length()" because "title" is null
        \tat com.acme.TeaserModel$Helper.help(TeaserModel.java:12)
        \tat com.acme.TeaserModel.init(TeaserModel.java:8)
        \t... 42 common frames omitted
        14.11.2026 17:07:21.001 *ERROR* [[0:0:0:0:0:0:0:1] [1794694040700] GET /content/acme/en.html HTTP/1.1] org.apache.sling.models.impl.ModelAdapterFactory Could not adapt to model
        org.apache.sling.models.factory.ModelClassException: Could not invoke @PostConstruct
        Caused by: java.lang.NullPointerException: Cannot invoke "String.length()" because "title" is null
        \tat com.acme.TeaserModel.init(TeaserModel.java:8)
        14.11.2026 17:08:00.000 [cm-p1-e2-aem-author-7f9c] *WARN* [sling-default-3] com.acme.TeaserModel cache miss for id 12345
        14.11.2026 17:09:00.000 *WARN* [qtp-1] org.apache.jackrabbit.oak.plugins.index.Cursor Traversed 10000 nodes
        14.11.2026 17:09:01.000 *DEBUG* [qtp-1] com.acme.TeaserModel ignored
        """;

    @BeforeEach
    void setUp() throws IOException {
        GraphRepository repo = new GraphRepository();
        graph = new GraphService(repo, new GraphAnalyzer(repo), new GraphExporter(repo));
        modelFile = dir.resolve("core/src/main/java/com/acme/TeaserModel.java");
        Files.createDirectories(modelFile.getParent());
        Files.writeString(modelFile, """
            package com.acme;
            public class TeaserModel {
                void init() {}
                class Helper { void help() {} }
            }
            """);
        new SourceParser(graph).parse(modelFile);
    }

    private List<LogEntry> read() throws IOException {
        Path log = dir.resolve("error.log");
        Files.writeString(log, LOG);
        return ErrorLogReader.read(log);
    }

    @Test
    void readsWarnAndErrorEntriesWithStackTraces() throws IOException {
        List<LogEntry> entries = read();

        assertThat(entries).extracting(LogEntry::level).containsExactly("ERROR", "ERROR", "WARN", "WARN");
        LogEntry first = entries.get(0);
        assertThat(first.thread()).isEqualTo("[0:0:0:0:0:0:0:1] [1794694040618] GET /content/acme/en.html?x=1 HTTP/1.1");
        assertThat(first.requestPath()).isEqualTo("/content/acme/en.html");
        assertThat(first.logger()).isEqualTo("org.apache.sling.models.impl.ModelAdapterFactory");
        assertThat(first.message()).isEqualTo("Could not adapt to model");
        assertThat(first.exceptionType()).as("innermost cause").isEqualTo("java.lang.NullPointerException");
        assertThat(first.frames()).containsExactly(
            "org.apache.sling.models.impl.ModelAdapterFactory", "com.acme.TeaserModel$Helper", "com.acme.TeaserModel");

        LogEntry cloud = entries.get(2);
        assertThat(cloud.logger()).as("Cloud Service pod prefix skipped").isEqualTo("com.acme.TeaserModel");
        assertThat(cloud.thread()).isEqualTo("sling-default-3");
        assertThat(cloud.requestPath()).isNull();
    }

    @Test
    void attributesToTopmostProjectFrameThenLogger() throws IOException {
        ErrorAttribution errors = ErrorAttribution.apply(read(), graph);

        assertThat(errors.total()).isEqualTo(4);
        assertThat(errors.errorCount()).isEqualTo(2);
        assertThat(errors.attributed()).isEqualTo(3);
        assertThat(errors.failingClasses()).singleElement().satisfies(c -> {
            assertThat(c.className()).isEqualTo("com.acme.TeaserModel");
            assertThat(c.errors()).isEqualTo(2);
            assertThat(c.warnings()).isEqualTo(1);
        });
        assertThat(graph.findNodeById("class:com.acme.TeaserModel").orElseThrow().getProperties())
            .containsEntry(ErrorAttribution.PROP_LOG_ERRORS, "2")
            .containsEntry(ErrorAttribution.PROP_LOG_WARNINGS, "1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void summaryGroupsRepeatsAndJoinsFindings() throws IOException {
        ErrorAttribution errors = ErrorAttribution.apply(read(), graph);
        Finding inModel = new Finding("forbidden-api", "DEPRECATED_WORKFLOW", "HIGH", modelFile.toString(), 3,
            "m", "t", "TYPE", null, null);
        Finding elsewhere = new Finding("forbidden-api", "DEPRECATED_WORKFLOW", "HIGH",
            dir.resolve("Other.java").toString(), 3, "m", "t", "TYPE", null, null);

        assertThat(errors.entriesFor(inModel)).isEqualTo(3L);
        assertThat(errors.entriesFor(elsewhere)).isNull();

        Map<String, Object> summary = errors.summary(List.of(inModel, elsewhere), 10);
        Map<String, Object> row = ((List<Map<String, Object>>) summary.get("byClass")).get(0);
        List<Map<String, Object>> signatures = (List<Map<String, Object>>) row.get("signatures");
        assertThat(signatures.get(0)).containsEntry("count", 2L)
            .containsEntry("signature", "java.lang.NullPointerException: Could not adapt to model");
        assertThat(signatures.get(1)).containsEntry("signature", "cache miss for id #");
        assertThat((Map<String, Long>) row.get("requestPaths")).containsEntry("/content/acme/en.html", 2L);
        assertThat((Map<String, Long>) row.get("attributedVia")).containsEntry("stack", 2L).containsEntry("logger", 1L);
        assertThat((List<?>) summary.get("findingsInFailingCode")).hasSize(1);
        assertThat((Map<String, Long>) summary.get("topUnattributedLoggers"))
            .containsOnlyKeys("org.apache.jackrabbit.oak.plugins.index.Cursor");
    }

    @Test
    void rejectsFilesThatAreNotAnErrorLog() throws IOException {
        Path notLog = dir.resolve("access.log");
        Files.writeString(notLog, "10.0.0.1 - - [13/Nov/2026:10:12:01 +0000] \"GET / HTTP/1.1\" 200 12\n");
        assertThatThrownBy(() -> ErrorLogReader.read(notLog)).hasMessageContaining("no log entries");
    }
}
