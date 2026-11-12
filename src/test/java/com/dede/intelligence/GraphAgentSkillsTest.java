package com.dede.intelligence;

import com.dede.domain.GraphAnalyzer;
import com.dede.domain.GraphExporter;
import com.dede.domain.GraphRepository;
import com.dede.domain.GraphService;
import com.dede.domain.model.CodeNode;
import com.dede.domain.model.NodeType;
import com.dede.domain.model.RelationshipType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GraphAgentSkillsTest {

    private GraphService graphService;
    private GraphAgentSkills skills;

    @BeforeEach
    void setUp() {
        GraphRepository repository = new GraphRepository();
        graphService = new GraphService(repository, new GraphAnalyzer(repository), new GraphExporter(repository));
        skills = new GraphAgentSkills(graphService);
    }

    @Test
    void doesNotCallSupportedComDayCqApisLegacy() {
        CodeNode cls = new CodeNode("class:com.acme.PageHelper", "PageHelper", NodeType.CLASS,
            "com.acme", "src/main/java/com/acme/PageHelper.java");
        cls.getProperties().put("imports", "com.day.cq.wcm.api.PageManager");
        graphService.addNode(cls);
        graphService.addNode(new CodeNode("pkg:com.day.cq.wcm.api", "com.day.cq.wcm.api",
            NodeType.PACKAGE, "com.day.cq.wcm.api", null));

        List<String> suggestions = skills.suggestRefactoring();

        assertThat(suggestions).noneMatch(s -> s.contains("com.day.cq"));
    }

    @Test
    void servletWithNoInProjectConsumerIsNotDeadCode() {
        CodeNode servlet = new CodeNode("class:com.acme.MyServlet", "MyServlet", NodeType.SLING_SERVLET,
            "com.acme", "src/main/java/com/acme/MyServlet.java");
        CodeNode svc = new CodeNode("svc:Servlet", "Servlet", NodeType.OSGI_SERVICE, "Servlet", null);
        graphService.addEdge(servlet, svc, RelationshipType.PROVIDES);

        assertThat(skills.suggestRefactoring()).noneMatch(s -> s.contains("Dead Code"));
    }

    @Test
    void projectServiceWithNoConsumerIsStillReported() {
        CodeNode impl = new CodeNode("class:com.acme.FooImpl", "FooImpl", NodeType.CLASS,
            "com.acme", "src/main/java/com/acme/FooImpl.java");
        CodeNode svc = new CodeNode("svc:com.acme.FooService", "com.acme.FooService",
            NodeType.OSGI_SERVICE, "com.acme.FooService", null);
        graphService.addEdge(impl, svc, RelationshipType.PROVIDES);

        assertThat(skills.suggestRefactoring()).anyMatch(s -> s.contains("Dead Code") && s.contains("FooService"));
    }
}
