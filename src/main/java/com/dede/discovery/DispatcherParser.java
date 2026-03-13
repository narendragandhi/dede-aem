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
public class DispatcherParser implements ProjectParser {

    private final GraphService graphService;
    private static final Pattern URL_PATTERN = Pattern.compile("/url\\s+\"([^\"]+)\"");

    public DispatcherParser(GraphService graphService) {
        this.graphService = graphService;
    }

    @Override
    public boolean supports(Path path) {
        return path.toString().endsWith(".any");
    }

    @Override
    public void parse(Path filterPath) {
        try {
            String content = Files.readString(filterPath);
            Matcher m = URL_PATTERN.matcher(content);
            while (m.find()) {
                String urlPattern = m.group(1);
                CodeNode filterNode = new CodeNode("dispatcher:" + urlPattern, urlPattern, NodeType.DISPATCHER_FILTER, urlPattern, filterPath.toString());
                graphService.addNode(filterNode);

                // Attempt to link to Sling Resource Types if the filter is specific
                if (urlPattern.startsWith("/bin/")) {
                    String servletPath = urlPattern.split("\\*")[0];
                    CodeNode servletNode = new CodeNode("endpoint:" + servletPath, servletPath, NodeType.OSGI_COMPONENT, servletPath, null);
                    graphService.addNode(servletNode);
                    graphService.addEdge(filterNode, servletNode, RelationshipType.REFERENCES, 50); // Pattern-based confidence
                }
            }
        } catch (Exception e) {
            // Silently ignore binary or non-dispatcher files
        }
    }
}
