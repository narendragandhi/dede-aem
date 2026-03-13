package com.dede.discovery;

import com.dede.domain.GraphService;
import com.dede.domain.model.CodeNode;
import com.dede.domain.model.NodeType;
import com.dede.domain.model.RelationshipType;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class HtlParser implements ProjectParser {

    private final GraphService graphService;
    private static final Pattern SLY_USE_PATTERN = Pattern.compile("data-sly-use\\.[a-zA-Z0-9_]+\\s*=\\s*\"([^\"]+)\"");

    public HtlParser(GraphService graphService) {
        this.graphService = graphService;
    }

    @Override
    public boolean supports(Path path) {
        String name = path.toString().toLowerCase();
        return name.endsWith(".html") || name.endsWith(".jsp");
    }

    @Override
    public void parse(Path htlPath) {
        try {
            String content = Files.readString(htlPath);
            Matcher m = SLY_USE_PATTERN.matcher(content);
            while (m.find()) {
                String modelClass = m.group(1);
                if (modelClass.contains(".")) {
                    CodeNode modelNode = new CodeNode("class:" + modelClass, modelClass, NodeType.SLING_MODEL, modelClass, null);
                    graphService.addNode(modelNode);

                    // Find parent resource type by directory convention
                    String parentPath = htlPath.getParent().toString();
                    if (parentPath.contains("/components/")) {
                        String resType = parentPath.substring(parentPath.indexOf("/components/") + 12);
                        CodeNode resTypeNode = new CodeNode("resType:" + resType, resType, NodeType.JCR_RESOURCE_TYPE, resType, null);
                        graphService.addEdge(resTypeNode, modelNode, RelationshipType.REFERENCES);
                    }
                }
            }
        } catch (Exception e) {
            // Silently ignore
        }
    }
}
