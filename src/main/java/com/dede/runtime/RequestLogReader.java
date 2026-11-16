package com.dede.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads HTTP requests from AEM's own logs, so request attribution works without any agent --
 * including on AEM as a Cloud Service, whose logs Cloud Manager lets you download or forward.
 *
 * <ul>
 *   <li>{@code request.log}: {@code 13/Nov/2026:10:12:01 +0000 [12] -> GET /content/a.html HTTP/1.1}
 *       paired by request id with {@code ... [12] <- 200 text/html 45ms};</li>
 *   <li>{@code access.log} (NCSA): {@code 10.0.0.1 - admin 13/Nov/2026:10:12:01 +0000 "GET /content/a.html HTTP/1.1" 200 1234 ...}.</li>
 * </ul>
 * Both formats may be mixed in one file; other lines are ignored.
 */
public final class RequestLogReader {

    /** One HTTP request. {@code status} and {@code durationMs} are 0 when the log doesn't say. */
    public record RequestRecord(String method, String path, int status, long durationMs) {}

    private static final Pattern REQUEST_IN = Pattern.compile("\\[(\\d+)] -> ([A-Z]+) (\\S+) HTTP/[\\d.]+");
    private static final Pattern REQUEST_OUT = Pattern.compile("\\[(\\d+)] <- (\\d{3}) \\S* ?(\\d+)ms");
    private static final Pattern ACCESS = Pattern.compile("\"([A-Z]+) (\\S+) HTTP/[\\d.]+\" (\\d{3})");

    private RequestLogReader() {}

    public static List<RequestRecord> read(Path file) throws IOException {
        List<RequestRecord> requests = new ArrayList<>();
        Map<String, Integer> openById = new HashMap<>();   // request.log id -> index in requests
        for (String line : Files.readAllLines(file)) {
            Matcher in = REQUEST_IN.matcher(line);
            if (in.find()) {
                openById.put(in.group(1), requests.size());
                requests.add(new RequestRecord(in.group(2), stripQuery(in.group(3)), 0, 0));
                continue;
            }
            Matcher out = REQUEST_OUT.matcher(line);
            if (out.find()) {
                Integer index = openById.remove(out.group(1));
                if (index != null) {
                    RequestRecord r = requests.get(index);
                    requests.set(index, new RequestRecord(r.method(), r.path(),
                        Integer.parseInt(out.group(2)), Long.parseLong(out.group(3))));
                }
                continue;
            }
            Matcher access = ACCESS.matcher(line);
            if (access.find()) {
                requests.add(new RequestRecord(access.group(1), stripQuery(access.group(2)),
                    Integer.parseInt(access.group(3)), 0));
            }
        }
        if (requests.isEmpty()) {
            throw new IOException("no requests found in " + file + " (expected AEM request.log or access.log lines)");
        }
        return requests;
    }

    static String stripQuery(String path) {
        int q = path.indexOf('?');
        return q >= 0 ? path.substring(0, q) : path;
    }
}
