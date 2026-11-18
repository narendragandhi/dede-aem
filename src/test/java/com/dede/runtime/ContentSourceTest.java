package com.dede.runtime;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContentSourceTest {

    @TempDir
    Path dir;

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private static ContentIndex empty() throws Exception {
        return ContentIndex.of("/content/none", "<jcr:root xmlns:jcr=\"http://www.jcp.org/jcr/1.0\"/>");
    }

    @Test
    void contentPackageFromPackageManager() throws Exception {
        Path zip = dir.resolve("site-content.zip");
        try (var out = new ZipOutputStream(Files.newOutputStream(zip))) {
            put(out, "META-INF/vault/filter.xml", "<workspaceFilter/>");
            put(out, "jcr_root/content/site/en/news/.content.xml", """
                <jcr:root xmlns:jcr="http://www.jcp.org/jcr/1.0" xmlns:sling="http://sling.apache.org/jcr/sling/1.0"
                          xmlns:cq="http://www.day.com/jcr/cq/1.0">
                  <jcr:content sling:resourceType="site/components/page" cq:template="/conf/site/settings/wcm/templates/article">
                    <root sling:resourceType="site/components/container">
                      <teaser sling:resourceType="site/components/teaser"/>
                    </root>
                  </jcr:content>
                </jcr:root>
                """);
            put(out, "jcr_root/conf/site/settings/wcm/templates/article/structure/.content.xml", """
                <jcr:root xmlns:jcr="http://www.jcp.org/jcr/1.0" xmlns:sling="http://sling.apache.org/jcr/sling/1.0">
                  <jcr:content><header sling:resourceType="site/components/header"/></jcr:content>
                </jcr:root>
                """);
        }
        ContentIndex index = empty();

        assertThat(ContentSource.addTo(index, zip.toString())).isEqualTo(2);
        assertThat(index.resourceTypesFor("/content/site/en/news")).containsExactlyInAnyOrder(
            "site/components/page", "site/components/container", "site/components/teaser", "site/components/header");
        assertThat(index.primaryResourceType("/content/site/en/news")).isEqualTo("site/components/page");
    }

    private static void put(ZipOutputStream out, String name, String body) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(body.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }

    private static final String HITS = """
        {"success":true,"results":4,"total":4,"more":false,"offset":0,"hits":[
          {"jcr:path":"/content/site/en/about/jcr:content","sling:resourceType":"site/components/page",
           "cq:template":"/conf/site/settings/wcm/templates/page"},
          {"jcr:path":"/content/site/en/about/jcr:content/root/teaser","sling:resourceType":"site/components/teaser"},
          {"jcr:path":"/content/site/en/about/jcr:content/root/xf","sling:resourceType":"site/components/experiencefragment",
           "fragmentVariationPath":"/content/experience-fragments/site/footer/master"},
          {"jcr:path":"/content/experience-fragments/site/footer/master/jcr:content/byline","sling:resourceType":"site/components/byline"}
        ]}
        """;

    @Test
    void savedQueryBuilderResponse() throws Exception {
        Path json = dir.resolve("nodes.json");
        Files.writeString(json, HITS);
        ContentIndex index = empty();

        assertThat(ContentSource.addTo(index, json.toString())).isEqualTo(4);
        assertThat(index.resourceTypesFor("/content/site/en/about")).containsExactlyInAnyOrder(
            "site/components/page", "site/components/teaser", "site/components/experiencefragment",
            "site/components/byline");   // through the experience fragment
        assertThat(index.resourceTypesFor("/content/site/en/about/jcr:content/root/teaser"))
            .containsExactly("site/components/teaser");
    }

    @Test
    void rejectsWhatIsNotContent() throws IOException {
        Path other = dir.resolve("other.json");
        Files.writeString(other, "{\"nodes\":[]}");
        assertThatThrownBy(() -> ContentSource.addTo(empty(), other.toString())).hasMessageContaining("not a QueryBuilder response");
        assertThatThrownBy(() -> ContentSource.addTo(empty(), dir.resolve("missing.zip").toString())).hasMessageContaining("not found");
    }

    @Test
    void liveQueryBuilderIsPagedAndCoversConf() throws Exception {
        List<String> queries = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bin/querybuilder.json", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            queries.add(query);
            String body;
            if (query.contains("path=/content/site") && query.contains("p.offset=0")) {
                body = "{\"success\":true,\"more\":true,\"hits\":[{\"jcr:path\":\"/content/site/en/jcr:content\","
                    + "\"sling:resourceType\":\"site/components/page\"}]}";
            } else if (query.contains("path=/content/site")) {
                body = "{\"success\":true,\"more\":false,\"hits\":[{\"jcr:path\":\"/content/site/en/jcr:content/root/teaser\","
                    + "\"sling:resourceType\":\"site/components/teaser\"}]}";
            } else {
                body = "{\"success\":true,\"more\":false,\"hits\":[]}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        ContentIndex index = empty();

        int added = ContentSource.addTo(index, "http://127.0.0.1:" + server.getAddress().getPort() + "/content/site");

        assertThat(added).isEqualTo(2);
        assertThat(queries).hasSize(3);
        assertThat(queries.get(1)).contains("p.offset=1");
        assertThat(queries.get(2)).contains("path=/conf/site");
        assertThat(index.resourceTypesFor("/content/site/en")).containsExactly("site/components/page", "site/components/teaser");
    }

    @Test
    void liveQueryReportsAuthFailureClearly() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/content/site";
        assertThatThrownBy(() -> ContentSource.addTo(empty(), url)).hasMessageContaining("DEDE_AEM_TOKEN");
    }
}
