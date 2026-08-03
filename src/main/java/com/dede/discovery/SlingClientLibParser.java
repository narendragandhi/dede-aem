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
import java.util.Arrays;

@Component
public class SlingClientLibParser {

    private final GraphService graphService;

    public SlingClientLibParser(GraphService graphService) {
        this.graphService = graphService;
    }

    public void parse(Path contentXmlPath) {
        try (InputStream is = Files.newInputStream(contentXmlPath)) {
            DocumentBuilderFactory factory = XmlSecurity.newSafeDocumentBuilderFactory();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(is);

            Element root = doc.getDocumentElement();
            String primaryType = root.getAttribute("jcr:primaryType");
            
            if ("cq:ClientLibraryFolder".equals(primaryType)) {
                String categories = root.getAttribute("categories");
                String dependencies = root.getAttribute("dependencies");
                String embeds = root.getAttribute("embed");

                if (!categories.isEmpty()) {
                    // One clientlib can have multiple categories
                    Arrays.stream(categories.split(","))
                        .map(String::trim)
                        .forEach(category -> {
                            CodeNode libNode = new CodeNode("clib:" + category, category, NodeType.CLIENTLIB, category, contentXmlPath.toString());
                            graphService.addNode(libNode);

                            // Dependencies
                            if (!dependencies.isEmpty()) {
                                Arrays.stream(dependencies.split(","))
                                    .map(String::trim)
                                    .forEach(dep -> {
                                        CodeNode depNode = new CodeNode("clib:" + dep, dep, NodeType.CLIENTLIB, dep, null);
                                        graphService.addNode(depNode);
                                        graphService.addEdge(libNode, depNode, RelationshipType.DEPENDS_ON);
                                    });
                            }

                            // Embeds
                            if (!embeds.isEmpty()) {
                                Arrays.stream(embeds.split(","))
                                    .map(String::trim)
                                    .forEach(emb -> {
                                        CodeNode embNode = new CodeNode("clib:" + emb, emb, NodeType.CLIENTLIB, emb, null);
                                        graphService.addNode(embNode);
                                        graphService.addEdge(libNode, embNode, RelationshipType.EMBEDS);
                                    });
                            }
                        });
                }
            }
        } catch (Exception e) {
            // Log and skip
        }
    }
}
