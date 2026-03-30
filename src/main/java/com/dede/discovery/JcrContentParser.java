package com.dede.discovery;

import com.dede.domain.GraphService;
import com.dede.domain.model.CodeNode;
import com.dede.domain.model.NodeType;
import com.dede.domain.model.RelationshipType;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;
import java.util.Set;

@Component
public class JcrContentParser implements ProjectParser {

    private final GraphService graphService;
    private final DocumentBuilderFactory factory;
    private static final Set<String> SENSITIVE_KEYS = Set.of("password", "secret", "token", "apikey", "credential");

    public JcrContentParser(GraphService graphService) {
        this.graphService = graphService;
        this.factory = DocumentBuilderFactory.newInstance();
        
        // SECURITY HARDENING: Prevent XXE
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
        } catch (Exception e) {
            System.err.println("Failed to configure secure XML parser");
        }
    }

    @Override
    public boolean supports(Path path) {
        return path.toString().endsWith(".content.xml");
    }

    @Override
    public void parse(Path xmlPath) {
        try {
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(xmlPath.toFile());
            Node root = doc.getDocumentElement();

            NamedNodeMap attrs = root.getAttributes();
            Node primaryTypeAttr = attrs.getNamedItem("jcr:primaryType");
            Node resourceTypeAttr = attrs.getNamedItem("sling:resourceType");
            Node resourceSuperTypeAttr = attrs.getNamedItem("sling:resourceSuperType");

            // Detect if this is a cq:Component definition (proxy component)
            boolean isComponentDefinition = primaryTypeAttr != null &&
                "cq:Component".equals(primaryTypeAttr.getNodeValue());

            if (resourceTypeAttr != null) {
                String resourceType = resourceTypeAttr.getNodeValue();
                CodeNode resTypeNode = new CodeNode("resType:" + resourceType, resourceType, NodeType.JCR_RESOURCE_TYPE, resourceType, null);
                graphService.addNode(resTypeNode);

                // Create a node for this specific content instance
                String contentPath = xmlPath.toString();
                CodeNode contentNode = new CodeNode("jcr:" + contentPath, contentPath, NodeType.JCR_COMPONENT, contentPath, contentPath);

                // SECRET SANITIZATION: Do not store sensitive properties
                for (int i = 0; i < attrs.getLength(); i++) {
                    Node attr = attrs.item(i);
                    String key = attr.getNodeName().toLowerCase();
                    if (SENSITIVE_KEYS.stream().noneMatch(key::contains)) {
                        contentNode.getProperties().put(attr.getNodeName(), attr.getNodeValue());
                    } else {
                        contentNode.getProperties().put(attr.getNodeName(), "[MASKED]");
                    }
                }

                graphService.addNode(contentNode);
                graphService.addEdge(contentNode, resTypeNode, RelationshipType.INSTANTIATED_BY);
            }

            // Handle proxy components that use sling:resourceSuperType
            if (isComponentDefinition && resourceSuperTypeAttr != null) {
                String superType = resourceSuperTypeAttr.getNodeValue();

                // Extract this component's resource type from path
                String pathStr = xmlPath.toString().replace("\\", "/");
                int appsIdx = pathStr.indexOf("/apps/");
                if (appsIdx == -1) {
                    appsIdx = pathStr.indexOf("jcr_root/apps/");
                    if (appsIdx != -1) appsIdx += 9;
                }

                if (appsIdx != -1) {
                    String afterApps = pathStr.substring(appsIdx + 6);
                    // Remove /.content.xml suffix
                    if (afterApps.endsWith("/.content.xml")) {
                        afterApps = afterApps.substring(0, afterApps.length() - 13);
                    }

                    // Create node for this component with proxy flag
                    CodeNode componentNode = new CodeNode("res:" + afterApps, afterApps,
                        NodeType.JCR_RESOURCE_TYPE, "Proxy to: " + superType, pathStr);
                    componentNode.getProperties().put("isProxy", "true");
                    componentNode.getProperties().put("sling:resourceSuperType", superType);
                    graphService.addNode(componentNode);

                    // Create relationship to super type
                    CodeNode superTypeNode = new CodeNode("res:" + superType, superType,
                        NodeType.JCR_RESOURCE_TYPE, superType, null);
                    graphService.addNode(superTypeNode);
                    graphService.addEdge(componentNode, superTypeNode, RelationshipType.EXTENDS);
                }
            }
        } catch (Exception e) {
            // Silently ignore malformed XML in non-JCR files
        }
    }
}
