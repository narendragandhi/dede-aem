package com.dede.discovery;

import com.dede.domain.GraphService;
import com.dede.domain.model.CodeNode;
import com.dede.domain.model.NodeType;
import com.dede.domain.model.RelationshipType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Parses AEM Workflow Model XML files to extract the workflow topology:
 *
 * <ul>
 *   <li>Creates a {@link NodeType#WORKFLOW_PROCESS} node for each workflow model.</li>
 *   <li>Creates child {@code WORKFLOW_PROCESS} nodes for each workflow step.</li>
 *   <li>Links the model to its steps with {@link RelationshipType#CONTAINS} edges.</li>
 *   <li>Flags steps that implement the legacy {@code WorkflowProcess} Java interface
 *       (incompatible with AEM Cloud Service) as violations.</li>
 * </ul>
 *
 * Supports:
 * - Classic workflow model XML ({@code var/workflow/models/.../.content.xml})
 * - Modern workflow model XML ({@code conf/global/settings/workflow/models/.../.content.xml})
 */
@Component
public class WorkflowModelParser implements ProjectParser {

    private static final Logger log = LoggerFactory.getLogger(WorkflowModelParser.class);

    // Legacy WorkflowProcess implementations — incompatible with Cloud Service
    private static final List<String> LEGACY_PROCESS_TYPES = List.of(
        "com.day.cq.workflow.exec.WorkflowProcess",
        "com.day.cq.dam.core.process",
        "com.day.cq.wcm.workflow.process",
        "com.day.cq.collab.blog.workflow",
        "com.adobe.granite.workflow.core.process"
    );

    // Well-known process types that are Cloud-compatible
    private static final List<String> CLOUD_SAFE_PREFIXES = List.of(
        "com.adobe.granite.workflow.steps",
        "com.adobe.cq.dam.aio",
        "com.adobe.aemds.guide"
    );

    private final GraphService graphService;
    private final DocumentBuilderFactory factory;

    public WorkflowModelParser(GraphService graphService) {
        this.graphService = graphService;
        this.factory = DocumentBuilderFactory.newInstance();
        // Security: disable external entities
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
        } catch (Exception e) {
            log.warn("Failed to configure secure XML parser: {}", e.getMessage());
        }
    }

    @Override
    public boolean supports(Path path) {
        if (!path.toString().endsWith(".xml")) return false;
        String pathStr = path.toString().replace('\\', '/');
        return pathStr.contains("workflow/models") || pathStr.contains("workflow/launcher");
    }

    @Override
    public void parse(Path path) {
        if (!supports(path) || !Files.exists(path)) return;
        try {
            String content = Files.readString(path);
            // Quick pre-filter — skip files that don't look like workflow models
            if (!content.contains("cq:WorkflowModel") && !content.contains("cq:WorkflowNode")) return;

            parseWorkflowXml(content, path);
        } catch (IOException e) {
            log.debug("Could not read {}: {}", path, e.getMessage());
        }
    }

    /**
     * Scans an entire directory tree for workflow model XML files.
     */
    public void parseAll(Path projectRoot) {
        if (!Files.exists(projectRoot)) return;
        try (Stream<Path> paths = Files.walk(projectRoot)) {
            paths.filter(Files::isRegularFile)
                 .filter(this::supports)
                 .forEach(this::parse);
        } catch (IOException e) {
            log.warn("Error walking {} for Workflow Model analysis: {}", projectRoot, e.getMessage());
        }
    }

    // -----------------------------------------------------------------------

    private void parseWorkflowXml(String content, Path filePath) {
        try {
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
            doc.getDocumentElement().normalize();

            // Find the workflow model node (jcr:primaryType="cq:WorkflowModel")
            String modelTitle = extractModelTitle(doc);
            String modelId = "wf-model:" + filePath.toString();
            String displayName = modelTitle != null ? modelTitle : filePath.getParent().getFileName().toString();

            CodeNode modelNode = new CodeNode(modelId, displayName, NodeType.WORKFLOW_PROCESS,
                "Workflow Model: " + displayName, filePath.toString());
            modelNode.getProperties().put("type", "model");
            modelNode.getProperties().put("filePath", filePath.toString());
            graphService.addNode(modelNode);

            log.debug("Parsed workflow model: {}", displayName);

            // Parse workflow nodes (steps)
            NodeList workflowNodes = doc.getElementsByTagName("*");
            List<CodeNode> steps = new ArrayList<>();

            for (int i = 0; i < workflowNodes.getLength(); i++) {
                if (workflowNodes.item(i) instanceof Element el) {
                    String primaryType = el.getAttribute("jcr:primaryType");
                    if ("cq:WorkflowNode".equals(primaryType)) {
                        CodeNode stepNode = parseWorkflowNode(el, modelId, filePath);
                        if (stepNode != null) {
                            graphService.addNode(stepNode);
                            graphService.addEdge(modelNode, stepNode, RelationshipType.CONTAINS);
                            steps.add(stepNode);
                        }
                    }
                }
            }

            modelNode.getProperties().put("stepCount", String.valueOf(steps.size()));

            // Flag legacy process types as violations
            for (CodeNode step : steps) {
                checkLegacyProcessCompatibility(step, modelNode);
            }

        } catch (Exception e) {
            log.debug("Failed to parse workflow XML {}: {}", filePath, e.getMessage());
        }
    }

    private String extractModelTitle(Document doc) {
        NodeList titleNodes = doc.getElementsByTagName("*");
        for (int i = 0; i < titleNodes.getLength(); i++) {
            if (titleNodes.item(i) instanceof Element el) {
                String title = el.getAttribute("jcr:title");
                if (!title.isEmpty()) return title;
            }
        }
        return null;
    }

    private CodeNode parseWorkflowNode(Element el, String modelId, Path filePath) {
        String nodeName = el.getAttribute("jcr:title");
        if (nodeName == null || nodeName.isEmpty()) {
            nodeName = el.getTagName();
        }

        String nodeType = el.getAttribute("type");
        String processType = el.getAttribute("PROCESS");
        if (processType == null || processType.isEmpty()) {
            // Try metadata child
            processType = extractProcessFromMetadata(el);
        }

        String stepId = modelId + "#" + nodeName.replace(" ", "_");
        CodeNode stepNode = new CodeNode(stepId, nodeName, NodeType.WORKFLOW_PROCESS,
            "Workflow Step: " + nodeName, filePath.toString());
        stepNode.getProperties().put("type", "step");
        stepNode.getProperties().put("stepType", nodeType != null ? nodeType : "PROCESS");
        if (processType != null && !processType.isEmpty()) {
            stepNode.getProperties().put("processClass", processType);
        }

        return stepNode;
    }

    private String extractProcessFromMetadata(Element workflowNode) {
        NodeList children = workflowNode.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child) {
                // Look for metaData element
                if (child.getTagName().equals("metaData") || child.getTagName().contains("metaData")) {
                    String process = child.getAttribute("PROCESS");
                    if (process != null && !process.isEmpty()) return process;
                }
            }
        }
        return null;
    }

    private void checkLegacyProcessCompatibility(CodeNode step, CodeNode modelNode) {
        String processClass = step.getProperties().get("processClass");
        if (processClass == null || processClass.isEmpty()) return;

        // Check if it's a known cloud-safe implementation
        boolean isSafe = CLOUD_SAFE_PREFIXES.stream().anyMatch(processClass::startsWith);
        if (isSafe) return;

        // Check if it matches known legacy patterns
        boolean isLegacy = LEGACY_PROCESS_TYPES.stream().anyMatch(processClass::startsWith);

        if (isLegacy) {
            String violationId = "violation:workflow:" + step.getId().hashCode();
            CodeNode violation = new CodeNode(
                violationId,
                "Legacy Workflow Process",
                NodeType.VULNERABILITY,
                "Step '" + step.getName() + "' uses legacy WorkflowProcess implementation: " + processClass +
                    ". Not compatible with AEM Cloud Service. Migrate to modern workflow steps.",
                step.getFilePath()
            );
            violation.getProperties().put("ruleId", "CST-6");
            violation.getProperties().put("severity", "HIGH");
            violation.getProperties().put("category", "WORKFLOW_PROCESS");
            violation.getProperties().put("processClass", processClass);
            graphService.addNode(violation);
            graphService.addEdge(modelNode, violation, RelationshipType.VIOLATES);
            log.warn("Legacy workflow process detected in '{}': {}", step.getName(), processClass);
        }
    }
}
