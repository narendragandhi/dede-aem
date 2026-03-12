package com.dede.analysis;

import com.dede.core.GraphService;
import com.dede.core.model.CodeNode;
import com.dede.core.model.NodeType;
import com.dede.core.model.RelationshipType;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class JcrContentParser {

    private final GraphService graphService;
    private final Pattern resourceTypePattern = Pattern.compile("sling:resourceType=\"([^\"]+)\"");
    private final Pattern primaryTypePattern = Pattern.compile("jcr:primaryType=\"([^\"]+)\"");

    public JcrContentParser(GraphService graphService) {
        this.graphService = graphService;
    }

    public void parse(Path xmlPath) {
        try {
            String content = Files.readString(xmlPath);
            String fileName = xmlPath.getFileName().toString();
            
            // Only parse .content.xml
            if (!fileName.equals(".content.xml")) return;

            // Determine if it's a page or a component instantiation
            NodeType type = content.contains("cq:Page") ? NodeType.JCR_PAGE : NodeType.JCR_COMPONENT;
            
            // Generate short ID based on path relative to jcr_root
            String fullPath = xmlPath.toString();
            String relativePath = fullPath.contains("jcr_root") 
                ? fullPath.substring(fullPath.indexOf("jcr_root") + 8) 
                : fileName;

            CodeNode contentNode = new CodeNode("jcr:" + relativePath, relativePath, type, relativePath, fullPath);
            graphService.addNode(contentNode);

            // Extract sling:resourceType
            Matcher m = resourceTypePattern.matcher(content);
            while (m.find()) {
                String resType = m.group(1);
                CodeNode resTypeNode = new CodeNode("res:" + resType, resType, NodeType.JCR_RESOURCE_TYPE, resType, null);
                graphService.addNode(resTypeNode);
                graphService.addEdge(contentNode, resTypeNode, RelationshipType.INSTANTIATED_BY);
            }

        } catch (Exception e) {
            // Silently ignore binary or corrupted XML
        }
    }
}
