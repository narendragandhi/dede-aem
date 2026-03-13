package com.dede.discovery;

import com.dede.domain.GraphService;
import com.dede.domain.model.CodeNode;
import com.dede.domain.model.NodeType;
import com.dede.domain.model.RelationshipType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SlingHtlParser {

    private final GraphService graphService;

    // Matches data-sly-use.model="com.example.MyModel" or data-sly-use="com.example.MyModel"
    private static final Pattern SLY_USE_PATTERN = Pattern.compile("data-sly-use(?:\\.[a-zA-Z0-9]+)?\\s*=\\s*\"([a-zA-Z0-9.]+)\"");
    
    // Matches resourceType='my/res/type' in data-sly-resource or data-sly-include
    private static final Pattern RESOURCE_TYPE_PATTERN = Pattern.compile("resourceType\\s*=\\s*['\"]([^'\"]+)['\"]");

    public SlingHtlParser(GraphService graphService) {
        this.graphService = graphService;
    }

    public void parse(Path htmlPath) {
        try {
            String content = Files.readString(htmlPath);
            String fileName = htmlPath.getFileName().toString();
            CodeNode htlNode = new CodeNode("htl:" + htmlPath, fileName, NodeType.HTL_FILE, htmlPath.toString(), htmlPath.toString());
            graphService.addNode(htlNode);

            // 1. Detect data-sly-use (Dependencies on Java)
            Matcher useMatcher = SLY_USE_PATTERN.matcher(content);
            while (useMatcher.find()) {
                String className = useMatcher.group(1);
                // Only track if it looks like a full Java class name
                if (className.contains(".")) {
                    CodeNode classNode = new CodeNode("class:" + className, className.substring(className.lastIndexOf('.') + 1), NodeType.CLASS, className, null);
                    graphService.addNode(classNode);
                    graphService.addEdge(htlNode, classNode, RelationshipType.USES);
                }
            }

            // 2. Detect resourceType inclusions (Dependencies on other Components)
            Matcher resMatcher = RESOURCE_TYPE_PATTERN.matcher(content);
            while (resMatcher.find()) {
                String resType = resMatcher.group(1);
                CodeNode resNode = new CodeNode("res:" + resType, resType, NodeType.JCR_RESOURCE_TYPE, resType, null);
                graphService.addNode(resNode);
                graphService.addEdge(htlNode, resNode, RelationshipType.REFERENCES);
            }

        } catch (IOException e) {
            // Log and skip
        }
    }
}
