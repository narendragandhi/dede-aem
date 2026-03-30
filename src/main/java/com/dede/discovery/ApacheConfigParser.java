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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for Apache HTTPD configurations (.conf, vhost).
 * Maps RewriteRules and VirtualHosts to the architectural graph.
 */
@Component
public class ApacheConfigParser implements ProjectParser {

    private static final Logger log = LoggerFactory.getLogger(ApacheConfigParser.class);

    private final GraphService graphService;

    // Matches RewriteRule source target [flags]
    private static final Pattern REWRITE_RULE_PATTERN = 
        Pattern.compile("RewriteRule\\s+\"?([^\"\\s]+)\"?\\s+\"?([^\"\\s]+)\"?", Pattern.CASE_INSENSITIVE);
    
    // Matches <VirtualHost *:80> or similar
    private static final Pattern VHOST_START_PATTERN = 
        Pattern.compile("<VirtualHost\\s+([^>]+)>", Pattern.CASE_INSENSITIVE);

    public ApacheConfigParser(GraphService graphService) {
        this.graphService = graphService;
    }

    @Override
    public boolean supports(Path path) {
        String fileName = path.getFileName().toString();
        return fileName.endsWith(".conf") || fileName.endsWith(".vhost") || fileName.contains("rewrite");
    }

    @Override
    public void parse(Path path) {
        try {
            String content = Files.readString(path);
            log.debug("Parsing Apache config: {}", path);

            String vhostName = "default";
            Matcher vhostMatcher = VHOST_START_PATTERN.matcher(content);
            if (vhostMatcher.find()) {
                vhostName = vhostMatcher.group(1);
            }

            Matcher rewriteMatcher = REWRITE_RULE_PATTERN.matcher(content);
            while (rewriteMatcher.find()) {
                String source = rewriteMatcher.group(1);
                String target = rewriteMatcher.group(2);

                CodeNode ruleNode = new CodeNode(
                    "apache:rewrite:" + path.getFileName() + ":" + source.hashCode(),
                    "Rewrite: " + source,
                    NodeType.DISPATCHER_FILTER, // Reuse dispatcher type for edge consistency
                    "Maps " + source + " -> " + target,
                    path.toString()
                );
                ruleNode.getProperties().put("source", source);
                ruleNode.getProperties().put("target", target);
                ruleNode.getProperties().put("vhost", vhostName);
                graphService.addNode(ruleNode);

                linkToInternalComponents(ruleNode, target);
            }

        } catch (IOException e) {
            log.error("Failed to parse Apache config {}: {}", path, e.getMessage());
        }
    }

    /**
     * Link the rewrite target to internal Sling/AEM components if possible.
     */
    private void linkToInternalComponents(CodeNode ruleNode, String target) {
        // Heuristic: if target contains a known app path, link to resource type
        if (target.contains("/content/") || target.contains("/apps/")) {
            graphService.getAllNodes().stream()
                .filter(n -> n.getType() == NodeType.JCR_RESOURCE_TYPE)
                .filter(n -> target.contains(n.getName()))
                .forEach(resNode -> 
                    graphService.addEdge(ruleNode, resNode, RelationshipType.EXPOSES, 60)
                );
        }
        
        // Link to Servlets via path
        if (target.contains("/bin/")) {
             graphService.getAllNodes().stream()
                .filter(n -> n.getType() == NodeType.SLING_SERVLET)
                .filter(n -> {
                    String servletPath = n.getProperties().get("path");
                    return servletPath != null && target.contains(servletPath);
                })
                .forEach(servletNode -> 
                    graphService.addEdge(ruleNode, servletNode, RelationshipType.EXPOSES, 70)
                );
        }
    }
}
