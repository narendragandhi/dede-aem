package com.dede.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * Adds a site's authored content to a {@link ContentIndex}, so requests for pages that exist only
 * in AEM can be attributed. Three kinds of source, by the shape of the argument:
 * <ul>
 *   <li>{@code site-content.zip}: a content package (Package Manager export of {@code /content/site}
 *       and, for editable templates, {@code /conf/site});</li>
 *   <li>{@code nodes.json}: a saved QueryBuilder response (see {@link #queryString});</li>
 *   <li>{@code http(s)://host:port/content/site}: QueryBuilder on a live instance, paged. Credentials
 *       come from the environment, never the command line: {@code DEDE_AEM_TOKEN} (bearer, e.g. a
 *       Cloud Service developer token) or {@code DEDE_AEM_USER} + {@code DEDE_AEM_PASSWORD}.</li>
 * </ul>
 * Only node paths and resource types are read -- never page text or assets.
 */
public final class ContentSource {

    private static final Logger log = LoggerFactory.getLogger(ContentSource.class);
    private static final int PAGE_SIZE = 2000;
    private static final int MAX_PAGES = 500;   // 1M nodes: a runaway guard, not a real limit

    private ContentSource() {}

    /** Adds {@code source} to {@code index}; returns the number of nodes or documents added. */
    public static int addTo(ContentIndex index, String source) throws IOException {
        if (source.startsWith("http://") || source.startsWith("https://")) {
            return fetchLive(index, URI.create(source));
        }
        Path file = Path.of(source);
        if (!Files.isRegularFile(file)) {
            throw new IOException("content source not found: " + source);
        }
        if (source.endsWith(".zip")) {
            int added = index.addPackage(file);
            if (added == 0) throw new IOException("no /content or /conf documents in package " + source);
            return added;
        }
        JsonNode response = new ObjectMapper().readTree(file.toFile());
        if (!response.has("hits")) {
            throw new IOException(source + " is not a QueryBuilder response (no \"hits\"); expected the output of "
                + "/bin/querybuilder.json?" + queryString("/content/<site>", 0, -1));
        }
        return index.addQueryBuilderHits(response);
    }

    /**
     * The QueryBuilder query that lists every node with a resource type under {@code path}, with
     * just the properties the index needs.
     */
    public static String queryString(String path, int offset, int limit) {
        return "path=" + encode(path)
            + "&property=sling:resourceType&property.operation=exists"
            + "&p.hits=selective&p.properties=" + encode("jcr:path sling:resourceType cq:template fragmentVariationPath")
            + "&p.offset=" + offset + "&p.limit=" + limit + "&p.guessTotal=true";
    }

    private static int fetchLive(ContentIndex index, URI uri) throws IOException {
        String contentPath = uri.getPath() == null || uri.getPath().isBlank() || uri.getPath().equals("/")
            ? "/content" : uri.getPath();
        String base = uri.getScheme() + "://" + uri.getAuthority();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        int added = 0;
        // The page content, then /conf for editable template structures.
        String confPath = contentPath.startsWith("/content/") ? "/conf/" + contentPath.split("/")[2] : "/conf";
        for (String path : List.of(contentPath, confPath)) {
            int offset = 0;
            for (int page = 0; page < MAX_PAGES; page++) {
                JsonNode response = get(client, base + "/bin/querybuilder.json?" + queryString(path, offset, PAGE_SIZE));
                added += index.addQueryBuilderHits(response);
                int hits = response.path("hits").size();
                offset += hits;
                if (!response.path("more").asBoolean(false) || hits == 0) break;
            }
            log.info("Content: {} node(s) with a resource type under {} on {}", offset, path, base);
        }
        return added;
    }

    private static JsonNode get(HttpClient client, String url) throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2)).GET();
        String token = System.getenv("DEDE_AEM_TOKEN");
        String user = System.getenv("DEDE_AEM_USER");
        if (token != null && !token.isBlank()) {
            request.header("Authorization", "Bearer " + token);
        } else if (user != null && !user.isBlank()) {
            String password = System.getenv().getOrDefault("DEDE_AEM_PASSWORD", "");
            request.header("Authorization", "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8)));
        }
        HttpResponse<String> response;
        try {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted fetching content from " + url, e);
        }
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new IOException("AEM refused the content query (" + response.statusCode()
                + "): set DEDE_AEM_TOKEN, or DEDE_AEM_USER and DEDE_AEM_PASSWORD");
        }
        if (response.statusCode() != 200) {
            throw new IOException("content query returned HTTP " + response.statusCode() + " for " + url);
        }
        JsonNode body = new ObjectMapper().readTree(response.body());
        if (!body.path("success").asBoolean(true)) {
            throw new IOException("QueryBuilder reported failure for " + url + ": " + body);
        }
        return body;
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
