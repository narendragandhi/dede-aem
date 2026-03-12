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
public class DispatcherParser {

    private final GraphService graphService;
    private final Pattern filterPattern = Pattern.compile("/url \"([^\"]+)\"");
    private final Pattern allowPattern = Pattern.compile("/type \"allow\"");

    public DispatcherParser(GraphService graphService) {
        this.graphService = graphService;
    }

    public void parse(Path filterPath) {
        try {
            String content = Files.readString(filterPath);
            String fileName = filterPath.getFileName().toString();

            // Identify dispatcher filters (.any files)
            if (!fileName.endsWith(".any") && !fileName.contains("filter")) return;

            Matcher m = filterPattern.matcher(content);
            while (m.find()) {
                String urlPattern = m.group(1);
                CodeNode filterNode = new CodeNode("filter:" + urlPattern, urlPattern, NodeType.DISPATCHER_FILTER, urlPattern, filterPath.toString());
                graphService.addNode(filterNode);

                // Attempt to link to Sling Resource Types if the filter is specific
                if (urlPattern.startsWith("/bin/")) {
                    String servletPath = urlPattern.split("\\*")[0];
                    CodeNode servletNode = new CodeNode("endpoint:" + servletPath, servletPath, NodeType.OSGI_COMPONENT, servletPath, null);
                    graphService.addNode(servletNode);
                    graphService.addEdge(filterNode, servletNode, RelationshipType.REFERENCES);
                }
            }
        } catch (Exception e) {
            // Silently ignore binary or non-dispatcher files
        }
    }
}
