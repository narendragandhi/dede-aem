package com.dede.runtime;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads WARN and ERROR entries, with their stack traces, from AEM's {@code error.log}.
 *
 * <p>Two line formats are read:
 * <ul>
 *   <li>AEM SDK / on-premise: {@code 14.11.2026 08:52:59.426 *ERROR* [thread] logger message}</li>
 *   <li>AEM as a Cloud Service (Cloud Manager download): the same, with the pod in brackets
 *       before the level: {@code 14.11.2026 08:52:59.426 [cm-p1-e2-aem-author-x] *ERROR* [thread] ...}</li>
 * </ul>
 * Lines that don't start a new entry (stack frames, {@code Caused by:}, wrapped messages)
 * belong to the entry above them. Sling names request threads after the request
 * ({@code [[0:0:0:0:0:0:0:1] [1794694040618] GET /content/a.html HTTP/1.1]}); the path is kept,
 * so an error can be tied to the URL that triggered it.
 */
public final class ErrorLogReader {

    /**
     * One WARN/ERROR entry. {@code exceptionType} is the innermost cause when the entry has a
     * stack trace (null otherwise); {@code frames} are the classes of every {@code at} line, top
     * first, across all causes; {@code requestPath} is null outside request threads.
     */
    public record LogEntry(String timestamp, String level, String thread, String logger, String message,
                           String exceptionType, List<String> frames, String requestPath) {}

    private static final Pattern HEADER = Pattern.compile(
        "^(\\d{2}\\.\\d{2}\\.\\d{4} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}) (?:\\[[^\\]]*] )?\\*([A-Z]+)\\* ");
    private static final Pattern FRAME = Pattern.compile("^\\s+at ([\\w$.]+)\\.[\\w$<>]+\\(");
    private static final Pattern EXCEPTION = Pattern.compile(
        "^(?:Caused by: )?([a-zA-Z_$][\\w$]*(?:\\.[\\w$]+)+(?:Exception|Error|Throwable)[\\w$]*)(?::|$)");
    private static final Pattern REQUEST_THREAD = Pattern.compile("\\] (?:[A-Z]+) (/\\S*) HTTP/[\\d.]+$");

    private ErrorLogReader() {}

    public static List<LogEntry> read(Path file) throws IOException {
        List<LogEntry> entries = new ArrayList<>();
        // Production logs run to gigabytes: stream them, and replace bad bytes rather than fail.
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8))) {
            Builder current = null;
            boolean sawHeader = false;
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher header = HEADER.matcher(line);
                if (header.find()) {
                    sawHeader = true;
                    if (current != null) entries.add(current.build());
                    String level = header.group(2);
                    current = ("ERROR".equals(level) || "WARN".equals(level))
                        ? Builder.parse(header.group(1), level, line.substring(header.end()))
                        : null;
                } else if (current != null) {
                    current.continuation(line);
                }
            }
            if (current != null) entries.add(current.build());
            if (!sawHeader) {
                throw new IOException("no log entries found in " + file
                    + " (expected AEM error.log lines like '14.11.2026 08:52:59.426 *ERROR* [thread] logger message')");
            }
        }
        return entries;
    }

    /** Request path from a Sling request thread name, or null. */
    static String requestPath(String thread) {
        Matcher m = REQUEST_THREAD.matcher(thread);
        return m.find() ? RequestLogReader.stripQuery(m.group(1)) : null;
    }

    private static final class Builder {
        private String timestamp;
        private String level;
        private String thread;
        private String logger;
        private String message;
        private String exceptionType;
        private final List<String> frames = new ArrayList<>();

        static Builder parse(String timestamp, String level, String rest) {
            Builder b = new Builder();
            b.timestamp = timestamp;
            b.level = level;
            // The thread is bracketed and may itself contain brackets (request threads).
            int end = -1;
            if (rest.startsWith("[")) {
                int depth = 0;
                for (int i = 0; i < rest.length(); i++) {
                    char c = rest.charAt(i);
                    if (c == '[') depth++;
                    else if (c == ']' && --depth == 0) { end = i; break; }
                }
            }
            String afterThread;
            if (end > 0) {
                b.thread = rest.substring(1, end);
                afterThread = rest.substring(end + 1).stripLeading();
            } else {
                b.thread = "";
                afterThread = rest;
            }
            int space = afterThread.indexOf(' ');
            b.logger = space > 0 ? afterThread.substring(0, space) : afterThread;
            b.message = space > 0 ? afterThread.substring(space + 1).strip() : "";
            return b;
        }

        void continuation(String line) {
            Matcher frame = FRAME.matcher(line);
            if (frame.find()) {
                frames.add(frame.group(1));
                return;
            }
            Matcher exception = EXCEPTION.matcher(line);
            if (exception.find()) {
                // The last one seen is the innermost cause: the actual failure.
                exceptionType = exception.group(1);
            }
        }

        LogEntry build() {
            return new LogEntry(timestamp, level, thread, logger, message, exceptionType, List.copyOf(frames),
                requestPath(thread));
        }
    }
}
