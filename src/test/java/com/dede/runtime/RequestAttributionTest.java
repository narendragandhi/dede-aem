package com.dede.runtime;

import com.dede.domain.GraphAnalyzer;
import com.dede.domain.GraphExporter;
import com.dede.domain.GraphRepository;
import com.dede.domain.GraphService;
import com.dede.domain.model.CodeNode;
import com.dede.domain.model.NodeType;
import com.dede.domain.model.RelationshipType;
import com.dede.runtime.RequestLogReader.RequestRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mirrors WKND's archetype setup, where these rules were first checked against real data:
 * a page servlet registered for one extension, a model bound through HTL, and a path servlet.
 */
class RequestAttributionTest {

    @TempDir
    Path dir;

    private GraphService graph;
    private ContentIndex content;

    private CodeNode cls(String fqcn, NodeType type) {
        CodeNode node = new CodeNode("class:" + fqcn, fqcn.substring(fqcn.lastIndexOf('.') + 1), type, fqcn, fqcn + ".java");
        graph.addNode(node);
        return node;
    }

    private CodeNode node(String id, String name, NodeType type) {
        CodeNode node = new CodeNode(id, name, type, name, null);
        graph.addNode(node);
        return node;
    }

    @BeforeEach
    void setUp() throws Exception {
        GraphRepository repo = new GraphRepository();
        graph = new GraphService(repo, new GraphAnalyzer(repo), new GraphExporter(repo));

        // @SlingServletResourceTypes(resourceTypes="site/components/page", extensions="txt")
        CodeNode pageServlet = cls("com.acme.TxtServlet", NodeType.SLING_SERVLET);
        graph.addEdge(pageServlet, node("resType:site/components/page", "site/components/page", NodeType.JCR_RESOURCE_TYPE),
            RelationshipType.HANDLES_RESOURCE_TYPE);
        graph.addEdge(pageServlet, node("ext:txt", "txt", NodeType.SLING_EXTENSION), RelationshipType.HANDLES_EXTENSION);
        graph.addEdge(pageServlet, node("method:GET", "GET", NodeType.METHOD), RelationshipType.HANDLES_EXTENSION);

        // teaser component: res: folder -DEFINES-> HTL -USES-> model
        CodeNode teaser = node("res:site/components/teaser", "site/components/teaser", NodeType.JCR_RESOURCE_TYPE);
        CodeNode htl = node("htl:teaser.html", "teaser.html", NodeType.HTL_FILE);
        graph.addEdge(teaser, htl, RelationshipType.DEFINES);
        graph.addEdge(htl, cls("com.acme.TeaserModel", NodeType.SLING_MODEL), RelationshipType.USES);

        // @Model(resourceType = "site/components/unused")
        graph.addEdge(cls("com.acme.UnusedModel", NodeType.SLING_MODEL),
            node("resType:site/components/unused", "site/components/unused", NodeType.JCR_RESOURCE_TYPE),
            RelationshipType.INSTANTIATED_BY);

        // @SlingServletPaths("/bin/acme/export")
        graph.addEdge(cls("com.acme.ExportServlet", NodeType.SLING_SERVLET),
            node("path:/bin/acme/export", "/bin/acme/export", NodeType.SLING_PATH), RelationshipType.HANDLES_PATH);

        content = ContentIndex.of("/content/site/en", """
            <jcr:root xmlns:jcr="http://www.jcp.org/jcr/1.0" xmlns:sling="http://sling.apache.org/jcr/sling/1.0"
                      jcr:primaryType="cq:Page">
              <jcr:content sling:resourceType="site/components/page">
                <root sling:resourceType="site/components/container">
                  <teaser sling:resourceType="site/components/teaser"/>
                </root>
              </jcr:content>
            </jcr:root>
            """);
    }

    private static RequestRecord get(String path) {
        return new RequestRecord("GET", path, 200, 1);
    }

    private static List<RequestRecord> times(int n, String path) {
        List<RequestRecord> list = new ArrayList<>();
        for (int i = 0; i < n; i++) list.add(get(path));
        return list;
    }

    @Test
    void pageRequestsRenderEveryComponentOnThePage() {
        RequestAttribution result = RequestAttribution.apply(times(3, "/content/site/en.html"), content, graph);

        assertThat(result.attributed()).isEqualTo(3);
        assertThat(result.requestsFor("com.acme.TeaserModel")).isEqualTo(3L);
        // The txt servlet is bound to the page type but only for .txt: not on .html requests.
        assertThat(result.requestsFor("com.acme.TxtServlet")).isNull();
        assertThat(graph.findNodeById("class:com.acme.TeaserModel").orElseThrow().getProperties())
            .containsEntry(RequestAttribution.PROP_REQUESTS, "3");
    }

    @Test
    void aServletMatchingThePagesOwnTypeReplacesTheHtlRender() {
        RequestAttribution result = RequestAttribution.apply(times(2, "/content/site/en.hello.txt"), content, graph);

        assertThat(result.requestsFor("com.acme.TxtServlet")).isEqualTo(2L);
        // Sling dispatched to the servlet: the page's components did not render.
        assertThat(result.requestsFor("com.acme.TeaserModel")).isNull();
    }

    @Test
    void componentRequestsRenderTheirSubtreeOnly() {
        RequestAttribution result = RequestAttribution.apply(
            List.of(get("/content/site/en/jcr:content/root/teaser.model.json")), content, graph);

        assertThat(result.requestsFor("com.acme.TeaserModel")).isEqualTo(1L);
    }

    @Test
    void pathServletsMatchExactPathExtensionAndSuffix() {
        RequestAttribution result = RequestAttribution.apply(List.of(
            get("/bin/acme/export"), get("/bin/acme/export.json"), get("/bin/acme/export/suffix"),
            get("/bin/acme/exporter")), content, graph);

        assertThat(result.requestsFor("com.acme.ExportServlet")).isEqualTo(3L);
        assertThat(result.attributed()).isEqualTo(3);
    }

    @Test
    void summaryListsUnattributedPathsAndNeverRenderedTypes() {
        RequestAttribution result = RequestAttribution.apply(List.of(
            get("/content/site/en.html"), get("/content/dam/site/a.jpg"), get("/content/dam/site/a.jpg")), content, graph);

        assertThat(result.resourceTypesNeverRendered()).containsExactly("site/components/unused");
        Map<String, Object> summary = result.summary(20);
        assertThat(summary).containsEntry("total", 3).containsEntry("attributed", 1);
        assertThat((List<?>) summary.get("topUnattributedPaths")).hasSize(1);
    }

    @Test
    void slingUrlDecomposition() {
        assertThat(RequestAttribution.resourcePath("/content/a/b.sel.html/suffix/x.y")).isEqualTo("/content/a/b");
        assertThat(RequestAttribution.resourcePath("/content/a/b")).isEqualTo("/content/a/b");
        assertThat(RequestAttribution.SlingUrl.parse("/content/a/b.s1.s2.json/suffix"))
            .isEqualTo(new RequestAttribution.SlingUrl("/content/a/b", List.of("s1", "s2"), "json"));
        assertThat(RequestAttribution.SlingUrl.parse("/content/a/b"))
            .isEqualTo(new RequestAttribution.SlingUrl("/content/a/b", List.of(), null));
    }

    // --- RequestLogReader ---

    @Test
    void readsAemRequestLogAndAccessLogLines() throws IOException {
        Path log = dir.resolve("request.log");
        Files.writeString(log, """
            13/Nov/2026:10:12:01 +0000 [7] -> GET /content/site/en.html?wcmmode=disabled HTTP/1.1
            13/Nov/2026:10:12:01 +0000 [8] -> POST /bin/acme/export HTTP/1.1
            13/Nov/2026:10:12:01 +0000 [7] <- 200 text/html 45ms
            13/Nov/2026:10:12:02 +0000 [8] <- 500 - 3ms
            10.0.0.1 - admin 13/Nov/2026:10:13:00 +0000 "GET /content/site/en.html HTTP/1.1" 200 5120 "-" "Mozilla/5.0"
            13.11.2026 10:13:01.000 *INFO* [main] some.other.Logger unrelated line
            """);

        assertThat(RequestLogReader.read(log)).containsExactly(
            new RequestRecord("GET", "/content/site/en.html", 200, 45),
            new RequestRecord("POST", "/bin/acme/export", 500, 3),
            new RequestRecord("GET", "/content/site/en.html", 200, 0));
    }

    @Test
    void aLogWithoutRequestsIsAnErrorNotAnEmptyResult() throws IOException {
        Path log = dir.resolve("error.log");
        Files.writeString(log, "13.11.2026 10:13:01.000 *ERROR* [main] x.Y boom\n");

        assertThatThrownBy(() -> RequestLogReader.read(log)).isInstanceOf(IOException.class)
            .hasMessageContaining("no requests found");
    }

    @Test
    void contentIndexIsBuiltFromTheProjectsJcrRoot() throws IOException {
        Path page = dir.resolve("ui.content/src/main/content/jcr_root/content/site/fr/.content.xml");
        Files.createDirectories(page.getParent());
        Files.writeString(page, """
            <jcr:root xmlns:jcr="http://www.jcp.org/jcr/1.0" xmlns:sling="http://sling.apache.org/jcr/sling/1.0">
              <jcr:content sling:resourceType="site/components/page"/>
            </jcr:root>
            """);

        ContentIndex index = ContentIndex.build(dir);
        assertThat(index.pageCount()).isEqualTo(1);
        assertThat(index.primaryResourceType("/content/site/fr")).isEqualTo("site/components/page");
    }

    // --- Found on a real AEM run with Adobe's WKND (2026-11-14), checked against trace ground truth ---

    @Test
    void htlInterfaceReferencesBindToTheImplementation() throws Exception {
        // data-sly-use="com.acme.Gallery" names the interface; GalleryImpl is what runs.
        CodeNode gallery = node("res:site/components/gallery", "site/components/gallery", NodeType.JCR_RESOURCE_TYPE);
        CodeNode htl = node("htl:gallery.html", "gallery.html", NodeType.HTL_FILE);
        graph.addEdge(gallery, htl, RelationshipType.DEFINES);
        graph.addEdge(htl, cls("com.acme.Gallery", NodeType.INTERFACE), RelationshipType.USES);
        graph.addEdge(cls("com.acme.GalleryImpl", NodeType.SLING_MODEL),
            node("interface:Gallery", "Gallery", NodeType.INTERFACE), RelationshipType.IMPLEMENTS);
        ContentIndex index = ContentIndex.of("/content/site/gallery", """
            <jcr:root xmlns:jcr="http://www.jcp.org/jcr/1.0" xmlns:sling="http://sling.apache.org/jcr/sling/1.0">
              <jcr:content sling:resourceType="site/components/page"><g sling:resourceType="site/components/gallery"/></jcr:content>
            </jcr:root>
            """);

        RequestAttribution result = RequestAttribution.apply(List.of(get("/content/site/gallery.html")), index, graph);
        assertThat(result.requestsFor("com.acme.GalleryImpl")).isEqualTo(1L);
        assertThat(result.requestsFor("com.acme.Gallery")).isNull();
    }

    @Test
    void pagesIncludeTheirTemplateStructureAndExperienceFragments() throws IOException {
        write("ui.content/src/main/content/jcr_root/content/site/article/.content.xml", """
            <jcr:root xmlns:jcr="http://www.jcp.org/jcr/1.0" xmlns:sling="http://sling.apache.org/jcr/sling/1.0"
                      xmlns:cq="http://www.day.com/jcr/cq/1.0">
              <jcr:content cq:template="/conf/site/settings/wcm/templates/article" sling:resourceType="site/components/page">
                <xf sling:resourceType="site/components/experiencefragment"
                    fragmentVariationPath="/content/experience-fragments/site/byline/master"/>
              </jcr:content>
            </jcr:root>
            """);
        write("ui.content/src/main/content/jcr_root/conf/site/settings/wcm/templates/article/structure/.content.xml", """
            <jcr:root xmlns:jcr="http://www.jcp.org/jcr/1.0" xmlns:sling="http://sling.apache.org/jcr/sling/1.0">
              <jcr:content><footer sling:resourceType="site/components/teaser"/></jcr:content>
            </jcr:root>
            """);
        write("ui.content/src/main/content/jcr_root/content/experience-fragments/site/byline/master/.content.xml", """
            <jcr:root xmlns:jcr="http://www.jcp.org/jcr/1.0" xmlns:sling="http://sling.apache.org/jcr/sling/1.0">
              <jcr:content><byline sling:resourceType="site/components/byline"/></jcr:content>
            </jcr:root>
            """);

        assertThat(ContentIndex.build(dir).resourceTypesFor("/content/site/article")).contains(
            "site/components/page", "site/components/experiencefragment",
            "site/components/teaser",    // from the template structure
            "site/components/byline");   // from the experience fragment
    }

    @Test
    void aPageDefinedInTwoContentPackagesIsTheUnionOfBoth() throws IOException {
        write("ui.content/src/main/content/jcr_root/content/site/home/.content.xml", """
            <jcr:root xmlns:jcr="http://www.jcp.org/jcr/1.0" xmlns:sling="http://sling.apache.org/jcr/sling/1.0">
              <jcr:content sling:resourceType="site/components/page"/>
            </jcr:root>
            """);
        write("ui.content.sample/src/main/content/jcr_root/content/site/home/.content.xml", """
            <jcr:root xmlns:jcr="http://www.jcp.org/jcr/1.0" xmlns:sling="http://sling.apache.org/jcr/sling/1.0">
              <jcr:content sling:resourceType="site/components/page"><list sling:resourceType="site/components/image-list"/></jcr:content>
            </jcr:root>
            """);

        assertThat(ContentIndex.build(dir).resourceTypesFor("/content/site/home"))
            .contains("site/components/page", "site/components/image-list");
    }

    private void write(String relative, String content) throws IOException {
        Path file = dir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
