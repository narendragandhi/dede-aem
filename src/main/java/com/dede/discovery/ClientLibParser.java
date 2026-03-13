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
public class ClientLibParser implements ProjectParser {

    private final GraphService graphService;
    private static final Pattern EMBED_PATTERN = Pattern.compile("embed\\s*=\\s*\\[([^\\]]+)\\]");

    public ClientLibParser(GraphService graphService) {
        this.graphService = graphService;
    }

    @Override
    public boolean supports(Path path) {
        return path.toString().endsWith(".content.xml") && path.toString().contains("clientlibs");
    }

    @Override
    public void parse(Path xmlPath) {
        try {
            String content = Files.readString(xmlPath);
            if (content.contains("cq:ClientLibraryFolder")) {
                Matcher m = Pattern.compile("categories=\"([^\"]+)\"").matcher(content);
                if (m.find()) {
                    String category = m.group(1);
                    CodeNode libNode = new CodeNode("clientlib:" + category, category, NodeType.CLIENTLIB, category, xmlPath.toString());
                    graphService.addNode(libNode);

                    Matcher embedMatcher = EMBED_PATTERN.matcher(content);
                    while (embedMatcher.find()) {
                        String embeds = embedMatcher.group(1);
                        for (String emb : embeds.split(",")) {
                            String target = emb.trim().replace("\"", "");
                            CodeNode targetNode = new CodeNode("clientlib:" + target, target, NodeType.CLIENTLIB, target, null);
                            graphService.addEdge(libNode, targetNode, RelationshipType.EMBEDS);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Silently ignore
        }
    }
}
