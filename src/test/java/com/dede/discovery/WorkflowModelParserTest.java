package com.dede.discovery;

import com.dede.domain.GraphAnalyzer;
import com.dede.domain.GraphExporter;
import com.dede.domain.GraphRepository;
import com.dede.domain.GraphService;
import com.dede.domain.model.CodeNode;
import com.dede.domain.model.NodeType;
import com.dede.domain.model.RelationshipType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for WorkflowModelParser — AEM workflow model topology analysis.
 */
class WorkflowModelParserTest {

    @TempDir
    Path tempDir;

    private GraphService graphService;
    private WorkflowModelParser parser;

    @BeforeEach
    void setUp() {
        GraphRepository repo = new GraphRepository();
        graphService = new GraphService(repo, new GraphAnalyzer(repo), new GraphExporter(repo));
        parser = new WorkflowModelParser(graphService);
    }

    // -----------------------------------------------------------------------
    // supports() contract
    // -----------------------------------------------------------------------

    @Test
    void supportsWorkflowModelXml() {
        assertThat(parser.supports(Path.of("var/workflow/models/dam/update_asset/.content.xml"))).isTrue();
        assertThat(parser.supports(Path.of("conf/global/settings/workflow/models/myflow/.content.xml"))).isTrue();
    }

    @Test
    void doesNotSupportNonWorkflowFiles() {
        assertThat(parser.supports(Path.of("content/dam/.content.xml"))).isFalse();
        assertThat(parser.supports(Path.of("MyServlet.java"))).isFalse();
        assertThat(parser.supports(Path.of("filter.xml"))).isFalse();
    }

    // -----------------------------------------------------------------------
    // Basic model parsing
    // -----------------------------------------------------------------------

    @Test
    void parsesWorkflowModelNode() throws IOException {
        Path wfFile = writeWorkflowXml("workflow/models/dam_update/.content.xml", """
            <?xml version="1.0" encoding="UTF-8"?>
            <jcr:root xmlns:jcr="http://www.jcp.org/jcr/1.0"
                      xmlns:cq="http://www.day.com/jcr/cq/1.0"
                      jcr:primaryType="cq:WorkflowModel"
                      jcr:title="DAM Update Asset">
            </jcr:root>
            """);

        parser.parse(wfFile);

        List<CodeNode> models = graphService.getAllNodes().stream()
            .filter(n -> n.getType() == NodeType.WORKFLOW_PROCESS)
            .toList();
        assertThat(models).isNotEmpty();
        assertThat(models.get(0).getName()).isEqualTo("DAM Update Asset");
    }

    @Test
    void skipsNonWorkflowXmlContent() throws IOException {
        Path wfFile = writeWorkflowXml("workflow/models/other/.content.xml", """
            <?xml version="1.0" encoding="UTF-8"?>
            <jcr:root xmlns:jcr="http://www.jcp.org/jcr/1.0"
                      jcr:primaryType="cq:Page">
            </jcr:root>
            """);

        parser.parse(wfFile);

        assertThat(graphService.getAllNodes().stream()
            .filter(n -> n.getType() == NodeType.WORKFLOW_PROCESS)
            .toList()).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Step parsing
    // -----------------------------------------------------------------------

    @Test
    void parsesWorkflowSteps() throws IOException {
        Path wfFile = writeWorkflowXml("workflow/models/myflow/.content.xml", """
            <?xml version="1.0" encoding="UTF-8"?>
            <jcr:root xmlns:jcr="http://www.jcp.org/jcr/1.0"
                      xmlns:cq="http://www.day.com/jcr/cq/1.0"
                      jcr:primaryType="cq:WorkflowModel"
                      jcr:title="My Flow">
                <nodes>
                    <step1 jcr:primaryType="cq:WorkflowNode"
                           jcr:title="Extract Metadata"
                           type="PROCESS"
                           PROCESS="com.day.cq.dam.core.process.ExtractMetadataProcess"/>
                    <step2 jcr:primaryType="cq:WorkflowNode"
                           jcr:title="Send Email"
                           type="PROCESS"
                           PROCESS="com.day.cq.dam.core.process.SendEmailProcess"/>
                </nodes>
            </jcr:root>
            """);

        parser.parse(wfFile);

        List<CodeNode> steps = graphService.getAllNodes().stream()
            .filter(n -> n.getType() == NodeType.WORKFLOW_PROCESS
                     && "step".equals(n.getProperties().get("type")))
            .toList();
        assertThat(steps).hasSize(2);
        assertThat(steps).extracting(CodeNode::getName)
            .containsExactlyInAnyOrder("Extract Metadata", "Send Email");
    }

    @Test
    void stepsLinkedToModelViaContainsEdge() throws IOException {
        Path wfFile = writeWorkflowXml("workflow/models/flow/.content.xml", """
            <?xml version="1.0" encoding="UTF-8"?>
            <jcr:root xmlns:jcr="http://www.jcp.org/jcr/1.0"
                      xmlns:cq="http://www.day.com/jcr/cq/1.0"
                      jcr:primaryType="cq:WorkflowModel"
                      jcr:title="My Flow">
                <nodes>
                    <step1 jcr:primaryType="cq:WorkflowNode"
                           jcr:title="Start"
                           type="START"/>
                </nodes>
            </jcr:root>
            """);

        parser.parse(wfFile);

        CodeNode model = graphService.getAllNodes().stream()
            .filter(n -> "model".equals(n.getProperties().get("type")))
            .findFirst().orElseThrow();

        Set<CodeNode> children = graphService.getRelatedNodes(model, RelationshipType.CONTAINS);
        assertThat(children).isNotEmpty();
    }

    @Test
    void stepCountStoredAsProperty() throws IOException {
        Path wfFile = writeWorkflowXml("workflow/models/counted/.content.xml", """
            <?xml version="1.0" encoding="UTF-8"?>
            <jcr:root xmlns:jcr="http://www.jcp.org/jcr/1.0"
                      xmlns:cq="http://www.day.com/jcr/cq/1.0"
                      jcr:primaryType="cq:WorkflowModel"
                      jcr:title="Counted Flow">
                <nodes>
                    <s1 jcr:primaryType="cq:WorkflowNode" jcr:title="Step1" type="PROCESS"/>
                    <s2 jcr:primaryType="cq:WorkflowNode" jcr:title="Step2" type="PROCESS"/>
                    <s3 jcr:primaryType="cq:WorkflowNode" jcr:title="Step3" type="PROCESS"/>
                </nodes>
            </jcr:root>
            """);

        parser.parse(wfFile);

        CodeNode model = graphService.getAllNodes().stream()
            .filter(n -> "model".equals(n.getProperties().get("type")))
            .findFirst().orElseThrow();

        assertThat(model.getProperties().get("stepCount")).isEqualTo("3");
    }

    // -----------------------------------------------------------------------
    // Legacy process violation detection
    // -----------------------------------------------------------------------

    @Test
    void flagsLegacyWorkflowProcessAsViolation() throws IOException {
        Path wfFile = writeWorkflowXml("workflow/models/legacy/.content.xml", """
            <?xml version="1.0" encoding="UTF-8"?>
            <jcr:root xmlns:jcr="http://www.jcp.org/jcr/1.0"
                      xmlns:cq="http://www.day.com/jcr/cq/1.0"
                      jcr:primaryType="cq:WorkflowModel"
                      jcr:title="Legacy Flow">
                <nodes>
                    <step1 jcr:primaryType="cq:WorkflowNode"
                           jcr:title="Legacy Step"
                           type="PROCESS"
                           PROCESS="com.day.cq.workflow.exec.WorkflowProcess"/>
                </nodes>
            </jcr:root>
            """);

        parser.parse(wfFile);

        List<CodeNode> violations = graphService.getAllNodes().stream()
            .filter(n -> n.getType() == NodeType.VULNERABILITY)
            .toList();
        assertThat(violations).isNotEmpty();
        assertThat(violations.get(0).getProperties().get("ruleId")).isEqualTo("CST-6");
        assertThat(violations.get(0).getProperties().get("category")).isEqualTo("WORKFLOW_PROCESS");
    }

    @Test
    void doesNotFlagCloudSafeProcessTypes() throws IOException {
        Path wfFile = writeWorkflowXml("workflow/models/safe/.content.xml", """
            <?xml version="1.0" encoding="UTF-8"?>
            <jcr:root xmlns:jcr="http://www.jcp.org/jcr/1.0"
                      xmlns:cq="http://www.day.com/jcr/cq/1.0"
                      jcr:primaryType="cq:WorkflowModel"
                      jcr:title="Safe Flow">
                <nodes>
                    <step1 jcr:primaryType="cq:WorkflowNode"
                           jcr:title="Safe Step"
                           type="PROCESS"
                           PROCESS="com.adobe.granite.workflow.steps.SomeCloudStep"/>
                </nodes>
            </jcr:root>
            """);

        parser.parse(wfFile);

        List<CodeNode> violations = graphService.getAllNodes().stream()
            .filter(n -> n.getType() == NodeType.VULNERABILITY)
            .toList();
        assertThat(violations).isEmpty();
    }

    @Test
    void violationLinkedToModelViaViolatesEdge() throws IOException {
        Path wfFile = writeWorkflowXml("workflow/models/viol/.content.xml", """
            <?xml version="1.0" encoding="UTF-8"?>
            <jcr:root xmlns:jcr="http://www.jcp.org/jcr/1.0"
                      xmlns:cq="http://www.day.com/jcr/cq/1.0"
                      jcr:primaryType="cq:WorkflowModel"
                      jcr:title="Viol Flow">
                <nodes>
                    <step1 jcr:primaryType="cq:WorkflowNode"
                           jcr:title="Bad Step"
                           type="PROCESS"
                           PROCESS="com.day.cq.wcm.workflow.process.LegacyStep"/>
                </nodes>
            </jcr:root>
            """);

        parser.parse(wfFile);

        CodeNode model = graphService.getAllNodes().stream()
            .filter(n -> "model".equals(n.getProperties().get("type")))
            .findFirst().orElseThrow();

        Set<CodeNode> violationsLinked = graphService.getRelatedNodes(model, RelationshipType.VIOLATES);
        assertThat(violationsLinked).isNotEmpty();
    }

    // -----------------------------------------------------------------------
    // parseAll directory scan
    // -----------------------------------------------------------------------

    @Test
    void parseAllScansWorkflowDirectory() throws IOException {
        writeWorkflowXml("workflow/models/flow1/.content.xml", """
            <?xml version="1.0" encoding="UTF-8"?>
            <jcr:root xmlns:jcr="http://www.jcp.org/jcr/1.0"
                      jcr:primaryType="cq:WorkflowModel"
                      jcr:title="Flow One"/>
            """);
        writeWorkflowXml("workflow/models/flow2/.content.xml", """
            <?xml version="1.0" encoding="UTF-8"?>
            <jcr:root xmlns:jcr="http://www.jcp.org/jcr/1.0"
                      jcr:primaryType="cq:WorkflowModel"
                      jcr:title="Flow Two"/>
            """);

        parser.parseAll(tempDir);

        List<CodeNode> models = graphService.getAllNodes().stream()
            .filter(n -> n.getType() == NodeType.WORKFLOW_PROCESS
                     && "model".equals(n.getProperties().get("type")))
            .toList();
        assertThat(models).hasSize(2);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private Path writeWorkflowXml(String relativePath, String content) throws IOException {
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }
}
