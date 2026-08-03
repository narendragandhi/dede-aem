package com.dede.discovery;

import com.dede.domain.GraphService;
import com.dede.domain.model.CodeNode;
import com.dede.domain.model.NodeType;
import com.dede.domain.model.RelationshipType;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Analyses Java source files to extract the Sling Job topology:
 *
 * <ul>
 *   <li>{@code @JobConsumer(slingJobTopics = "...")} — creates a {@code SLING_JOB} node
 *       for the topic and links the component with a {@link RelationshipType#PROCESSES} edge.</li>
 *   <li>{@code JobManager.addJob(topic, ...)} call sites — links the calling component
 *       with a {@link RelationshipType#PRODUCES} edge to the same topic node.</li>
 * </ul>
 *
 * <p>Topic nodes use the topic string as their ID so that multiple producers/consumers
 * targeting the same topic automatically share the same graph node.
 */
@Component
public class SlingJobParser implements ProjectParser {

    private static final Logger log = LoggerFactory.getLogger(SlingJobParser.class);

    private static final String JOB_CONSUMER_ANNOTATION = "JobConsumer";
    private static final String JOB_TOPICS_ATTR         = "slingJobTopics";
    private static final String JOB_MANAGER_CLASS       = "JobManager";
    private static final String ADD_JOB_METHOD          = "addJob";

    private final GraphService graphService;
    private final JavaParser   javaParser;

    public SlingJobParser(GraphService graphService) {
        this.graphService = graphService;
        this.javaParser   = new JavaParser(new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE));
    }

    @Override
    public boolean supports(Path path) {
        return path.toString().endsWith(".java");
    }

    @Override
    public void parse(Path path) {
        if (!supports(path) || !Files.exists(path)) return;
        try {
            String source = Files.readString(path);
            // Quick pre-filter — only parse files that look relevant
            if (!source.contains(JOB_CONSUMER_ANNOTATION) && !source.contains(ADD_JOB_METHOD)) return;

            var parseResult = javaParser.parse(source);
            if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) return;

            CompilationUnit cu = parseResult.getResult().get();
            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls ->
                analyseClass(cls, path.toString()));

        } catch (IOException e) {
            log.debug("Could not parse {}: {}", path, e.getMessage());
        }
    }

    /**
     * Scans an entire project directory tree.
     */
    public void parseAll(Path projectRoot) {
        if (!Files.exists(projectRoot)) return;
        try (Stream<Path> paths = Files.walk(projectRoot)) {
            paths.filter(Files::isRegularFile)
                 .filter(this::supports)
                 .forEach(this::parse);
        } catch (IOException e) {
            log.warn("Error walking {} for Sling Job analysis: {}", projectRoot, e.getMessage());
        }
    }

    // -----------------------------------------------------------------------

    private void analyseClass(ClassOrInterfaceDeclaration cls, String filePath) {
        String className = cls.getFullyQualifiedName().orElse(cls.getNameAsString());

        // 1. @JobConsumer(slingJobTopics = "topic") — this class processes jobs
        cls.getAnnotationByName(JOB_CONSUMER_ANNOTATION).ifPresent(ann -> {
            List<String> topics = extractTopics(ann);
            if (topics.isEmpty()) return;

            CodeNode componentNode = getOrCreateComponent(className, filePath);
            for (String topic : topics) {
                CodeNode jobNode = getOrCreateJobTopic(topic);
                graphService.addEdge(componentNode, jobNode, RelationshipType.PROCESSES);
                log.debug("JobConsumer '{}' processes topic '{}'", className, topic);
            }
        });

        // 2. JobManager.addJob(topic, ...) call sites
        cls.findAll(MethodCallExpr.class).forEach(call -> {
            if (!ADD_JOB_METHOD.equals(call.getNameAsString())) return;
            boolean looksLikeJobManager = call.getScope()
                .map(s -> s.toString().contains(JOB_MANAGER_CLASS) ||
                          s.toString().toLowerCase().contains("jobmanager"))
                .orElse(false);
            if (!looksLikeJobManager) return;

            // First argument is the topic string (may be a literal or a constant)
            call.getArguments().stream()
                .findFirst()
                .filter(arg -> arg instanceof StringLiteralExpr)
                .map(arg -> ((StringLiteralExpr) arg).asString())
                .ifPresent(topic -> {
                    CodeNode componentNode = getOrCreateComponent(className, filePath);
                    CodeNode jobNode = getOrCreateJobTopic(topic);
                    graphService.addEdge(componentNode, jobNode, RelationshipType.PRODUCES);
                    log.debug("Component '{}' produces topic '{}'", className, topic);
                });
        });
    }

    private List<String> extractTopics(AnnotationExpr ann) {
        List<String> topics = new ArrayList<>();
        if (ann.isSingleMemberAnnotationExpr()) {
            // @JobConsumer("topic") or @JobConsumer({"t1","t2"})
            collectStringLiterals(ann.asSingleMemberAnnotationExpr().getMemberValue(), topics);
        } else if (ann.isNormalAnnotationExpr()) {
            ann.asNormalAnnotationExpr().getPairs().stream()
                .filter(p -> JOB_TOPICS_ATTR.equals(p.getNameAsString()))
                .forEach(p -> collectStringLiterals(p.getValue(), topics));
        }
        return topics;
    }

    private void collectStringLiterals(com.github.javaparser.ast.expr.Expression expr, List<String> out) {
        if (expr instanceof StringLiteralExpr sle) {
            out.add(sle.asString());
        } else if (expr instanceof com.github.javaparser.ast.expr.ArrayInitializerExpr arr) {
            arr.getValues().forEach(v -> collectStringLiterals(v, out));
        }
    }

    private CodeNode getOrCreateComponent(String className, String filePath) {
        String id = "comp:" + className;
        return graphService.getAllNodes().stream()
            .filter(n -> id.equals(n.getId()))
            .findFirst()
            .orElseGet(() -> {
                String simpleName = className.contains(".")
                    ? className.substring(className.lastIndexOf('.') + 1)
                    : className;
                CodeNode node = new CodeNode(id, simpleName, NodeType.OSGI_COMPONENT, className, filePath);
                graphService.addNode(node);
                return node;
            });
    }

    private CodeNode getOrCreateJobTopic(String topic) {
        String id = "job:" + topic;
        return graphService.getAllNodes().stream()
            .filter(n -> id.equals(n.getId()))
            .findFirst()
            .orElseGet(() -> {
                CodeNode node = new CodeNode(id, topic, NodeType.SLING_JOB, topic, null);
                node.getProperties().put("topic", topic);
                graphService.addNode(node);
                return node;
            });
    }
}
