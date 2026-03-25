package com.dede.discovery;

import com.dede.domain.GraphService;
import com.dede.domain.model.CodeNode;
import com.dede.domain.model.NodeType;
import com.dede.domain.model.RelationshipType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses HTL (Sightly) templates and creates graph relationships.
 * Creates:
 * - HTL_FILE nodes for each template
 * - USES edges to Sling Models (data-sly-use)
 * - REFERENCES edges to Resource Types (data-sly-resource, data-sly-include)
 * - DEFINES edge from HTL to its parent Component (resource type)
 */
@Component
public class SlingHtlParser {

    private static final Logger log = LoggerFactory.getLogger(SlingHtlParser.class);

    private final GraphService graphService;

    // Matches data-sly-use.model="com.example.MyModel" or data-sly-use="com.example.MyModel"
    private static final Pattern SLY_USE_PATTERN = Pattern.compile(
        "data-sly-use(?:\\.[a-zA-Z0-9_]+)?\\s*=\\s*[\"']([^\"']+)[\"']");

    // Matches resourceType='my/res/type' in data-sly-resource or data-sly-include
    private static final Pattern RESOURCE_TYPE_PATTERN = Pattern.compile(
        "resourceType\\s*=\\s*['\"]([^'\"]+)['\"]");

    // Matches data-sly-resource with path: data-sly-resource="${'path' @ resourceType='type'}"
    private static final Pattern SLY_RESOURCE_PATTERN = Pattern.compile(
        "data-sly-resource\\s*=\\s*\"[^\"]*resourceType\\s*=\\s*'([^']+)'");

    // Matches data-sly-include for other HTL files
    private static final Pattern SLY_INCLUDE_PATTERN = Pattern.compile(
        "data-sly-include\\s*=\\s*[\"']([^\"']+\\.html)[\"']");

    // Matches data-sly-call for template calls
    private static final Pattern SLY_CALL_PATTERN = Pattern.compile(
        "data-sly-call\\s*=\\s*\"\\$\\{([^.]+)\\.");

    public SlingHtlParser(GraphService graphService) {
        this.graphService = graphService;
    }

    public void parse(Path htmlPath) {
        try {
            String content = Files.readString(htmlPath);
            String fileName = htmlPath.getFileName().toString();
            String fullPath = htmlPath.toString();

            CodeNode htlNode = new CodeNode("htl:" + fullPath, fileName, NodeType.HTL_FILE, fullPath, fullPath);
            graphService.addNode(htlNode);

            // Link HTL to its parent AEM Component (resource type)
            linkToParentComponent(htlNode, htmlPath);

            // 1. Detect data-sly-use (Dependencies on Java models or JS Use-API)
            Matcher useMatcher = SLY_USE_PATTERN.matcher(content);
            while (useMatcher.find()) {
                String useValue = useMatcher.group(1);
                handleSlyUse(htlNode, useValue);
            }

            // 2. Detect resourceType in data-sly-resource
            Matcher resTypeMatcher = RESOURCE_TYPE_PATTERN.matcher(content);
            while (resTypeMatcher.find()) {
                String resType = resTypeMatcher.group(1);
                createResourceTypeReference(htlNode, resType);
            }

            // 3. Detect data-sly-resource with resourceType
            Matcher slyResMatcher = SLY_RESOURCE_PATTERN.matcher(content);
            while (slyResMatcher.find()) {
                String resType = slyResMatcher.group(1);
                createResourceTypeReference(htlNode, resType);
            }

            // 4. Detect data-sly-include for other HTL files
            Matcher includeMatcher = SLY_INCLUDE_PATTERN.matcher(content);
            while (includeMatcher.find()) {
                String includePath = includeMatcher.group(1);
                CodeNode includeNode = new CodeNode("htl:" + includePath, includePath,
                    NodeType.HTL_FILE, includePath, null);
                graphService.addNode(includeNode);
                graphService.addEdge(htlNode, includeNode, RelationshipType.REFERENCES);
            }

            log.trace("Parsed HTL file: {}", htmlPath);

        } catch (IOException e) {
            log.warn("Failed to parse HTL file: {}", htmlPath, e);
        }
    }

    /**
     * Links HTL file to its parent AEM Component based on folder structure.
     * Path like /apps/myproject/components/mycomp/mycomp.html -> res:myproject/components/mycomp
     */
    private void linkToParentComponent(CodeNode htlNode, Path htmlPath) {
        String pathStr = htmlPath.toString().replace("\\", "/");

        // Look for /apps/ or /jcr_root/apps/
        int appsIdx = pathStr.indexOf("/apps/");
        if (appsIdx == -1) {
            appsIdx = pathStr.indexOf("jcr_root/apps/");
            if (appsIdx != -1) appsIdx += 9; // Move past jcr_root
        }

        if (appsIdx != -1) {
            // Extract resource type from path (parent directory)
            String afterApps = pathStr.substring(appsIdx + 6); // After "/apps/"
            int lastSlash = afterApps.lastIndexOf('/');
            if (lastSlash > 0) {
                String resType = afterApps.substring(0, lastSlash);
                CodeNode componentNode = new CodeNode("res:" + resType, resType,
                    NodeType.JCR_RESOURCE_TYPE, resType, null);
                graphService.addNode(componentNode);

                // HTL is part of this component
                graphService.addEdge(componentNode, htlNode, RelationshipType.DEFINES);

                log.trace("Linked HTL {} to component {}", htlNode.getName(), resType);
            }
        }
    }

    /**
     * Handles data-sly-use values which can be:
     * - Fully qualified Java class: com.example.MyModel
     * - Relative path to JS Use-API: ./logic.js
     * - Simple name referencing a template: templateLib.template
     */
    private void handleSlyUse(CodeNode htlNode, String useValue) {
        if (useValue.contains(".") && !useValue.startsWith("./") && !useValue.startsWith("../")) {
            // Looks like a Java class name
            if (Character.isUpperCase(useValue.charAt(useValue.lastIndexOf('.') + 1))) {
                String simpleName = useValue.substring(useValue.lastIndexOf('.') + 1);
                CodeNode classNode = new CodeNode("class:" + useValue, simpleName,
                    NodeType.SLING_MODEL, useValue, null);
                graphService.addNode(classNode);
                graphService.addEdge(htlNode, classNode, RelationshipType.USES);
            }
        } else if (useValue.endsWith(".js")) {
            // JS Use-API
            CodeNode jsNode = new CodeNode("js:" + useValue, useValue,
                NodeType.HTL_FILE, useValue, null);
            graphService.addNode(jsNode);
            graphService.addEdge(htlNode, jsNode, RelationshipType.USES);
        }
    }

    /**
     * Creates a REFERENCES edge from HTL to a resource type.
     */
    private void createResourceTypeReference(CodeNode htlNode, String resType) {
        CodeNode resNode = new CodeNode("res:" + resType, resType,
            NodeType.JCR_RESOURCE_TYPE, resType, null);
        graphService.addNode(resNode);
        graphService.addEdge(htlNode, resNode, RelationshipType.REFERENCES);
    }
}
