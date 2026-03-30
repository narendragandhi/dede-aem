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
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enhanced HTL (Sightly) template parser.
 *
 * Creates:
 * - HTL_FILE nodes for each template
 * - USES edges to Sling Models (data-sly-use)
 * - REFERENCES edges to Resource Types (data-sly-resource, data-sly-include)
 * - DEFINES edge from HTL to its parent Component (resource type)
 *
 * Enhanced features:
 * - Detects data-sly-test, data-sly-list, data-sly-repeat usage
 * - Parses data-sly-template definitions and data-sly-call invocations
 * - Extracts embedded JavaScript expressions ${...}
 * - Links HTL to clientlib dependencies (data-sly-use.clientlib)
 * - Tracks template complexity metrics
 */
@Component
public class SlingHtlParser {

    private static final Logger log = LoggerFactory.getLogger(SlingHtlParser.class);

    private final GraphService graphService;

    // Matches data-sly-use.model="com.example.MyModel" or data-sly-use="com.example.MyModel"
    // Also handles: data-sly-use.model="${'com.example.MyModel' @ param=value}"
    private static final Pattern SLY_USE_PATTERN = Pattern.compile(
        "data-sly-use(?:\\.([a-zA-Z0-9_]+))?\\s*=\\s*[\"'](?:\\$\\{\\s*'?)?([^\"'@}]+)");

    // Matches resourceType='my/res/type' or resourceType = 'type' (flexible spacing)
    private static final Pattern RESOURCE_TYPE_PATTERN = Pattern.compile(
        "resourceType\\s*=\\s*['\"]([^'\"]+)['\"]");

    // Matches data-sly-resource with resourceType - handles various formats:
    // data-sly-resource="${ '.' @ resourceType = 'core/wcm/components/image/v2/image' }"
    // data-sly-resource="${resource @ resourceType='my/type'}"
    private static final Pattern SLY_RESOURCE_PATTERN = Pattern.compile(
        "data-sly-resource\\s*=\\s*\"\\$\\{[^}]*resourceType\\s*=\\s*'([^']+)'");

    // Matches data-sly-include for other HTL files
    private static final Pattern SLY_INCLUDE_PATTERN = Pattern.compile(
        "data-sly-include\\s*=\\s*[\"']([^\"']+\\.html)[\"']");

    // Matches data-sly-call for template calls: data-sly-call="${templateLib.myTemplate @ ...}"
    private static final Pattern SLY_CALL_PATTERN = Pattern.compile(
        "data-sly-call\\s*=\\s*\"\\$\\{\\s*([a-zA-Z0-9_]+)\\.([a-zA-Z0-9_]+)");

    // Matches data-sly-template definitions: data-sly-template.myTemplate="${@ param1, param2}"
    private static final Pattern SLY_TEMPLATE_PATTERN = Pattern.compile(
        "data-sly-template\\.([a-zA-Z0-9_]+)\\s*=\\s*\"\\$\\{\\s*@\\s*([^}]*)\\}\"");

    // Matches data-sly-test conditions: data-sly-test="${condition}" or data-sly-test.varName="${expr}"
    // Handles negation: data-sly-test="${!byline.empty}"
    private static final Pattern SLY_TEST_PATTERN = Pattern.compile(
        "data-sly-test(?:\\.([a-zA-Z0-9_]+))?\\s*=\\s*\"\\$\\{\\s*([^}]+?)\\s*\\}\"");

    // Matches data-sly-list: data-sly-list.item="${model.items}"
    private static final Pattern SLY_LIST_PATTERN = Pattern.compile(
        "data-sly-list(?:\\.([a-zA-Z0-9_]+))?\\s*=\\s*\"\\$\\{\\s*([^}]+?)\\s*\\}\"");

    // Matches data-sly-repeat: data-sly-repeat.item="${model.items}"
    private static final Pattern SLY_REPEAT_PATTERN = Pattern.compile(
        "data-sly-repeat(?:\\.([a-zA-Z0-9_]+))?\\s*=\\s*\"\\$\\{\\s*([^}]+?)\\s*\\}\"");

    // Matches HTL expressions: ${expression}
    private static final Pattern EXPRESSION_PATTERN = Pattern.compile(
        "\\$\\{([^}]+)\\}");

    // Matches clientlib use: data-sly-use.clientlib="/libs/granite/sightly/templates/clientlib.html"
    private static final Pattern CLIENTLIB_USE_PATTERN = Pattern.compile(
        "data-sly-use\\.clientlib\\s*=\\s*[\"']([^\"']+)[\"']");

    // Matches data-sly-call for clientlib: data-sly-call="${clientlib.js @ categories='...'}
    private static final Pattern CLIENTLIB_CALL_PATTERN = Pattern.compile(
        "data-sly-call\\s*=\\s*\"\\$\\{\\s*clientlib\\.(css|js|all)\\s*@\\s*categories\\s*=\\s*['\"]([^'\"]+)['\"]");

    // Matches inline clientlib categories
    private static final Pattern CLIENTLIB_CATEGORIES_PATTERN = Pattern.compile(
        "categories\\s*=\\s*['\"]([^'\"]+)['\"]");

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

            // Track template complexity metrics
            HtlMetrics metrics = new HtlMetrics();

            // Link HTL to its parent AEM Component (resource type)
            linkToParentComponent(htlNode, htmlPath);

            // 1. Detect data-sly-use (Dependencies on Java models or JS Use-API)
            Matcher useMatcher = SLY_USE_PATTERN.matcher(content);
            while (useMatcher.find()) {
                String varName = useMatcher.group(1); // May be null
                String useValue = useMatcher.group(2);
                handleSlyUse(htlNode, useValue, varName);
                metrics.useCount++;
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
                metrics.resourceCount++;
            }

            // 4. Detect data-sly-include for other HTL files
            Matcher includeMatcher = SLY_INCLUDE_PATTERN.matcher(content);
            while (includeMatcher.find()) {
                String includePath = includeMatcher.group(1);
                CodeNode includeNode = new CodeNode("htl:" + includePath, includePath,
                    NodeType.HTL_FILE, includePath, null);
                graphService.addNode(includeNode);
                graphService.addEdge(htlNode, includeNode, RelationshipType.REFERENCES);
                metrics.includeCount++;
            }

            // 5. Detect data-sly-template definitions
            Matcher templateMatcher = SLY_TEMPLATE_PATTERN.matcher(content);
            while (templateMatcher.find()) {
                String templateName = templateMatcher.group(1);
                String params = templateMatcher.group(2);
                handleTemplateDefinition(htlNode, templateName, params);
                metrics.templateDefCount++;
            }

            // 6. Detect data-sly-call invocations
            Matcher callMatcher = SLY_CALL_PATTERN.matcher(content);
            while (callMatcher.find()) {
                String libName = callMatcher.group(1);
                String templateName = callMatcher.group(2);
                handleTemplateCall(htlNode, libName, templateName);
                metrics.templateCallCount++;
            }

            // 7. Detect data-sly-test conditions
            Matcher testMatcher = SLY_TEST_PATTERN.matcher(content);
            while (testMatcher.find()) {
                String varName = testMatcher.group(1);
                String expression = testMatcher.group(2);
                extractExpressionReferences(htlNode, expression);
                metrics.testCount++;
            }

            // 8. Detect data-sly-list iterations
            Matcher listMatcher = SLY_LIST_PATTERN.matcher(content);
            while (listMatcher.find()) {
                String expression = listMatcher.group(2);
                extractExpressionReferences(htlNode, expression);
                metrics.listCount++;
            }

            // 9. Detect data-sly-repeat iterations
            Matcher repeatMatcher = SLY_REPEAT_PATTERN.matcher(content);
            while (repeatMatcher.find()) {
                String expression = repeatMatcher.group(2);
                extractExpressionReferences(htlNode, expression);
                metrics.repeatCount++;
            }

            // 10. Detect clientlib dependencies
            parseClientlibDependencies(htlNode, content);

            // 11. Extract all HTL expressions for analysis
            Matcher exprMatcher = EXPRESSION_PATTERN.matcher(content);
            while (exprMatcher.find()) {
                metrics.expressionCount++;
            }

            // Store metrics as properties
            htlNode.getProperties().put("useCount", String.valueOf(metrics.useCount));
            htlNode.getProperties().put("testCount", String.valueOf(metrics.testCount));
            htlNode.getProperties().put("listCount", String.valueOf(metrics.listCount));
            htlNode.getProperties().put("templateDefCount", String.valueOf(metrics.templateDefCount));
            htlNode.getProperties().put("templateCallCount", String.valueOf(metrics.templateCallCount));
            htlNode.getProperties().put("expressionCount", String.valueOf(metrics.expressionCount));
            htlNode.getProperties().put("complexity", String.valueOf(metrics.calculateComplexity()));

            log.debug("Parsed HTL file: {} (complexity: {}, expressions: {})",
                    fileName, metrics.calculateComplexity(), metrics.expressionCount);

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
     * - Path to another HTL template: /libs/granite/sightly/templates/clientlib.html
     * - Simple name referencing a template: templateLib.template
     */
    private void handleSlyUse(CodeNode htlNode, String useValue, String varName) {
        if (useValue.contains(".") && !useValue.startsWith("./") && !useValue.startsWith("../") && !useValue.startsWith("/")) {
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
        } else if (useValue.endsWith(".html")) {
            // HTL template library
            CodeNode templateLib = new CodeNode("htl:" + useValue, useValue,
                NodeType.HTL_FILE, useValue, null);
            if (varName != null) {
                templateLib.getProperties().put("alias", varName);
            }
            graphService.addNode(templateLib);
            graphService.addEdge(htlNode, templateLib, RelationshipType.USES);
        }
    }

    /**
     * Handles data-sly-template definitions.
     * Creates a node representing the template that can be called.
     */
    private void handleTemplateDefinition(CodeNode htlNode, String templateName, String params) {
        String templateId = htlNode.getId() + "#" + templateName;
        CodeNode templateNode = new CodeNode(templateId, templateName,
            NodeType.HTL_FILE, "Template: " + templateName, htlNode.getFilePath());
        templateNode.getProperties().put("isTemplate", "true");
        templateNode.getProperties().put("params", params.trim());
        graphService.addNode(templateNode);
        graphService.addEdge(htlNode, templateNode, RelationshipType.DEFINES);
    }

    /**
     * Handles data-sly-call invocations.
     * Links to the called template.
     */
    private void handleTemplateCall(CodeNode htlNode, String libName, String templateName) {
        // The libName refers to a variable defined via data-sly-use
        // We create a reference to indicate template usage
        String callId = "template-call:" + libName + "." + templateName;
        CodeNode callNode = new CodeNode(callId, libName + "." + templateName,
            NodeType.HTL_FILE, "Template call: " + libName + "." + templateName, null);
        callNode.getProperties().put("isTemplateCall", "true");
        callNode.getProperties().put("library", libName);
        callNode.getProperties().put("template", templateName);
        graphService.addNode(callNode);
        graphService.addEdge(htlNode, callNode, RelationshipType.REFERENCES);
    }

    /**
     * Extracts references from HTL expressions.
     * Looks for model/property access patterns like model.property or properties.title
     */
    private void extractExpressionReferences(CodeNode htlNode, String expression) {
        // Common patterns to look for
        if (expression.contains("properties.") || expression.contains("currentPage.") ||
            expression.contains("resource.") || expression.contains("request.")) {
            // These are Sling/AEM context objects, no need to create references
            return;
        }

        // Look for model references (e.g., model.getItems, myModel.title)
        Pattern modelAccess = Pattern.compile("([a-zA-Z][a-zA-Z0-9_]*)\\.");
        Matcher m = modelAccess.matcher(expression);
        // Track seen model names to avoid duplicates
        Set<String> seenModels = new HashSet<>();
        while (m.find()) {
            String modelName = m.group(1);
            // Skip common reserved words and context objects
            if (isReservedWord(modelName) || seenModels.contains(modelName)) {
                continue;
            }
            seenModels.add(modelName);
            // This could be a reference to a use-object
            htlNode.getProperties().compute("usedModels", (k, v) ->
                v == null ? modelName : v + "," + modelName);
        }
    }

    /**
     * Parses clientlib dependencies from HTL content.
     */
    private void parseClientlibDependencies(CodeNode htlNode, String content) {
        // Look for clientlib calls
        Matcher clientlibCallMatcher = CLIENTLIB_CALL_PATTERN.matcher(content);
        while (clientlibCallMatcher.find()) {
            String type = clientlibCallMatcher.group(1); // css, js, or all
            String categories = clientlibCallMatcher.group(2);

            // Create nodes for each clientlib category
            for (String category : categories.split(",")) {
                category = category.trim();
                if (!category.isEmpty()) {
                    CodeNode clientlibNode = new CodeNode("clientlib:" + category, category,
                        NodeType.CLIENTLIB, "Clientlib: " + category + " (" + type + ")", null);
                    clientlibNode.getProperties().put("type", type);
                    graphService.addNode(clientlibNode);
                    graphService.addEdge(htlNode, clientlibNode, RelationshipType.USES);
                }
            }
        }

        // Also look for inline category references
        Matcher categoriesMatcher = CLIENTLIB_CATEGORIES_PATTERN.matcher(content);
        Set<String> foundCategories = new HashSet<>();
        while (categoriesMatcher.find()) {
            String categories = categoriesMatcher.group(1);
            for (String category : categories.split(",")) {
                category = category.trim();
                if (!category.isEmpty() && !foundCategories.contains(category)) {
                    foundCategories.add(category);
                    CodeNode clientlibNode = new CodeNode("clientlib:" + category, category,
                        NodeType.CLIENTLIB, "Clientlib: " + category, null);
                    graphService.addNode(clientlibNode);
                    graphService.addEdge(htlNode, clientlibNode, RelationshipType.USES);
                }
            }
        }
    }

    private boolean isReservedWord(String word) {
        return Set.of(
            "properties", "currentPage", "resource", "request", "response",
            "component", "designer", "currentDesign", "currentStyle",
            "wcmmode", "editContext", "pageProperties", "inheritedPageProperties",
            "true", "false", "null", "item", "itemList", "index"
        ).contains(word.toLowerCase());
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

    /**
     * Internal class to track HTL template metrics.
     */
    private static class HtlMetrics {
        int useCount = 0;
        int testCount = 0;
        int listCount = 0;
        int repeatCount = 0;
        int resourceCount = 0;
        int includeCount = 0;
        int templateDefCount = 0;
        int templateCallCount = 0;
        int expressionCount = 0;

        /**
         * Calculate a complexity score based on HTL features used.
         * Higher scores indicate more complex templates.
         */
        int calculateComplexity() {
            return useCount * 2 +
                   testCount * 1 +
                   listCount * 2 +
                   repeatCount * 2 +
                   resourceCount * 3 +
                   includeCount * 2 +
                   templateDefCount * 3 +
                   templateCallCount * 2 +
                   (expressionCount / 5); // Every 5 expressions adds 1 point
        }
    }
}
