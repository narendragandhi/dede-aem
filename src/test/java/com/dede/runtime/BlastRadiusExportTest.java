package com.dede.runtime;

import com.dede.cloud.CloudFindings.Finding;
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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Edge shapes copied from a real WKND scan (BylineImpl). */
class BlastRadiusExportTest {

    private GraphService graph;
    private ContentIndex content;
    private final Path root = Path.of("/proj");

    private CodeNode node(String id, String name, NodeType type, String file) {
        CodeNode n = new CodeNode(id, name, type, name, file);
        graph.addNode(n);
        return graph.findNodeById(id).orElseThrow();
    }

    @BeforeEach
    void setUp() throws Exception {
        GraphRepository repo = new GraphRepository();
        graph = new GraphService(repo, new GraphAnalyzer(repo), new GraphExporter(repo));
        CodeNode impl = node("class:site.models.impl.BylineImpl", "site.models.impl.BylineImpl", NodeType.SLING_MODEL,
            "/proj/core/BylineImpl.java");
        CodeNode iface = node("class:site.models.Byline", "site.models.Byline", NodeType.INTERFACE, "/proj/core/Byline.java");
        CodeNode simpleNameIface = node("interface:Byline", "Byline", NodeType.INTERFACE, null);
        CodeNode htl = node("htl:apps/site/components/byline/byline.html", "byline.html", NodeType.HTL_FILE, null);
        CodeNode type = node("res:site/components/byline", "site/components/byline", NodeType.JCR_RESOURCE_TYPE, null);
        CodeNode adaptable = node("adaptable:SlingHttpServletRequest", "SlingHttpServletRequest", NodeType.CLASS, null);
        CodeNode pkg = node("pkg:site.models.impl", "site.models.impl", NodeType.PACKAGE, null);
        CodeNode other = node("class:site.Other", "site.Other", NodeType.CLASS, "/proj/core/Other.java");

        graph.addEdge(impl, simpleNameIface, RelationshipType.IMPLEMENTS);       // recorded by simple name
        graph.addEdge(htl, iface, RelationshipType.USES);                       // data-sly-use of the interface
        graph.addEdge(type, htl, RelationshipType.DEFINES);
        graph.addEdge(impl, type, RelationshipType.INSTANTIATED_BY);            // model -> the type rendering through it
        graph.addEdge(adaptable, impl, RelationshipType.ADAPTS_TO);             // not a dependency
        graph.addEdge(pkg, impl, RelationshipType.CONTAINS);                    // not a dependency
        graph.addEdge(impl, other, RelationshipType.CALLS);                     // impl depends on Other, not vice versa

        content = ContentIndex.of("/content/site/en/article", """
            <jcr:root xmlns:jcr="http://www.jcp.org/jcr/1.0" xmlns:sling="http://sling.apache.org/jcr/sling/1.0">
              <jcr:content sling:resourceType="site/components/page"><byline sling:resourceType="site/components/byline"/></jcr:content>
            </jcr:root>
            """);
    }

    private Map<String, Object> export(String name, RequestAttribution requests, List<Finding> findings) {
        return BlastRadiusExport.build(name, graph, content, requests, findings, root);
    }

    @SuppressWarnings("unchecked")
    private static List<String> ids(Map<String, Object> export) {
        return ((List<Map<String, Object>>) export.get("nodes")).stream().map(n -> (String) n.get("id")).toList();
    }

    @Test
    void followsDependencyDirectionPerRelationshipType() {
        Map<String, Object> export = export("BylineImpl", null, List.of());

        assertThat(ids(export)).containsExactly(
            "class:site.models.impl.BylineImpl",
            "class:site.models.Byline",                       // through the interface it implements (resolved)
            "res:site/components/byline",                      // INSTANTIATED_BY, reversed
            "htl:apps/site/components/byline/byline.html")     // uses the interface
            .doesNotContain("adaptable:SlingHttpServletRequest", "pkg:site.models.impl", "class:site.Other");
    }

    @Test
    @SuppressWarnings("unchecked")
    void addsThePagesAndTheirRequestsAndTheHottestPath() {
        RequestAttribution requests = RequestAttribution.apply(List.of(
            new RequestRecord("GET", "/content/site/en/article.html", 200, 3),
            new RequestRecord("GET", "/content/site/en/article.html", 200, 3)), content, graph);
        Finding finding = new Finding("cloud-readiness", "DEPRECATED_API", "MEDIUM", "/proj/core/BylineImpl.java", 0,
            "m", null, null, null, null);

        Map<String, Object> export = export("site.models.impl.BylineImpl", requests, List.of(finding));

        Map<String, Object> summary = (Map<String, Object>) export.get("summary");
        assertThat(summary).containsEntry("dependents", 3).containsEntry("pages", 1)
            .containsEntry("observedRequests", 2L).containsEntry("findings", 1);
        assertThat((List<Map<String, Object>>) export.get("pages")).singleElement()
            .satisfies(p -> assertThat(p).containsEntry("path", "/content/site/en/article").containsEntry("requests", 2L));
        assertThat((List<Map<String, Object>>) export.get("path")).extracting(s -> s.getOrDefault("id", s.get("path")))
            .containsExactly("class:site.models.impl.BylineImpl", "res:site/components/byline", "/content/site/en/article");
        Map<String, Object> rootNode = (Map<String, Object>) export.get("root");
        assertThat(rootNode).containsEntry("name", "BylineImpl").containsEntry("file", "core/BylineImpl.java");
        assertThat((Map<String, Long>) rootNode.get("traffic")).containsEntry("requests", 2L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void withoutRequestDataCountsAreAbsentNotZero() {
        Map<String, Object> export = export("BylineImpl", null, List.of());
        assertThat((Map<String, Object>) export.get("summary")).containsEntry("observedRequests", null);
        assertThat(((List<Map<String, Object>>) export.get("pages")).get(0)).containsEntry("requests", null);
    }

    @Test
    void unknownNamesAreErrors() {
        assertThatThrownBy(() -> export("Nope", null, List.of())).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("No node or class named 'Nope'");
    }
}
