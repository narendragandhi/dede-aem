package com.dede.discovery;

import com.dede.domain.GraphService;
import com.dede.domain.model.CodeNode;
import com.dede.domain.model.NodeType;
import com.dede.domain.model.RelationshipType;
import com.dede.security.XmlSecurity;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class SlingResourceParser {

    private final GraphService graphService;

    public SlingResourceParser(GraphService graphService) {
        this.graphService = graphService;
    }

    public void parse(Path contentXmlPath) {
        try (InputStream is = Files.newInputStream(contentXmlPath)) {
            DocumentBuilderFactory factory = XmlSecurity.newSafeDocumentBuilderFactory();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(is);

            Element root = doc.getDocumentElement();
            String resourceType = root.getAttribute("sling:resourceType");
            String superType = root.getAttribute("sling:resourceSuperType");

            if (!resourceType.isEmpty()) {
                CodeNode resNode = new CodeNode("res:" + resourceType, resourceType, NodeType.JCR_RESOURCE_TYPE, resourceType, contentXmlPath.toString());
                graphService.addNode(resNode);

                if (!superType.isEmpty()) {
                    CodeNode superNode = new CodeNode("res:" + superType, superType, NodeType.JCR_RESOURCE_TYPE, superType, null);
                    graphService.addNode(superNode);
                    graphService.addEdge(resNode, superNode, RelationshipType.REFERENCES);
                }
            }
        } catch (Exception e) {
            // Log and skip
        }
    }
}
