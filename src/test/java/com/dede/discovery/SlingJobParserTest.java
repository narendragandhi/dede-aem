package com.dede.discovery;

import com.dede.domain.GraphAnalyzer;
import com.dede.domain.GraphExporter;
import com.dede.domain.GraphRepository;
import com.dede.domain.GraphService;
import com.dede.domain.model.CodeNode;
import com.dede.domain.model.NodeType;
import com.dede.domain.model.RelationshipType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for Strand 5: SlingJobParser — Sling Job topology analysis.
 */
class SlingJobParserTest {

    @TempDir
    Path tempDir;

    private GraphService graphService;
    private SlingJobParser parser;

    @BeforeEach
    void setUp() {
        GraphRepository repo = new GraphRepository();
        graphService = new GraphService(repo, new GraphAnalyzer(repo), new GraphExporter(repo));
        parser = new SlingJobParser(graphService);
    }

    // -----------------------------------------------------------------------
    // @JobConsumer detection
    // -----------------------------------------------------------------------

    @Test
    void detectsJobConsumerAnnotationWithSingleTopic() throws IOException {
        Path file = writeJava("MyJobConsumer.java", """
            package com.example;
            import org.apache.sling.event.jobs.consumer.JobConsumer;
            @JobConsumer(slingJobTopics = "com/example/job/process")
            public class MyJobConsumer implements JobConsumer {
                public JobResult process(Job job) { return JobResult.OK; }
            }
            """);

        parser.parse(file);

        List<CodeNode> jobNodes = graphService.getAllNodes().stream()
            .filter(n -> n.getType() == NodeType.SLING_JOB)
            .toList();
        assertThat(jobNodes).hasSize(1);
        assertThat(jobNodes.get(0).getName()).isEqualTo("com/example/job/process");
    }

    @Test
    void jobConsumerCreatesProcessesEdge() throws IOException {
        Path file = writeJava("EventConsumer.java", """
            package com.example;
            @org.apache.sling.event.jobs.consumer.JobConsumer(slingJobTopics = "my/topic")
            public class EventConsumer {}
            """);

        parser.parse(file);

        CodeNode topicNode = findJobTopic("my/topic");
        assertThat(topicNode).isNotNull();

        Set<CodeNode> processors = graphService.getInboundRelatedNodes(topicNode, RelationshipType.PROCESSES);
        assertThat(processors).isNotEmpty();
        assertThat(processors.iterator().next().getType()).isEqualTo(NodeType.OSGI_COMPONENT);
    }

    @Test
    void detectsMultipleTopicsInArrayAnnotation() throws IOException {
        Path file = writeJava("MultiConsumer.java", """
            package com.example;
            @org.apache.sling.event.jobs.consumer.JobConsumer(
                slingJobTopics = {"topic/one", "topic/two"}
            )
            public class MultiConsumer {}
            """);

        parser.parse(file);

        List<CodeNode> jobNodes = graphService.getAllNodes().stream()
            .filter(n -> n.getType() == NodeType.SLING_JOB)
            .toList();
        assertThat(jobNodes).extracting(CodeNode::getName)
            .containsExactlyInAnyOrder("topic/one", "topic/two");
    }

    // -----------------------------------------------------------------------
    // JobManager.addJob() detection
    // -----------------------------------------------------------------------

    @Test
    void detectsJobManagerAddJobCall() throws IOException {
        Path file = writeJava("MyScheduler.java", """
            package com.example;
            import org.apache.sling.event.jobs.JobManager;
            public class MyScheduler {
                private JobManager jobManager;
                public void trigger() {
                    jobManager.addJob("com/example/job/render", null);
                }
            }
            """);

        parser.parse(file);

        CodeNode topicNode = findJobTopic("com/example/job/render");
        assertThat(topicNode).isNotNull();

        Set<CodeNode> producers = graphService.getInboundRelatedNodes(topicNode, RelationshipType.PRODUCES);
        assertThat(producers).isNotEmpty();
    }

    @Test
    void producesEdgeLinksToCorrectTopic() throws IOException {
        Path file = writeJava("Publisher.java", """
            package com.example;
            public class Publisher {
                private org.apache.sling.event.jobs.JobManager jobManager;
                void run() { jobManager.addJob("com/acme/send-email", null); }
            }
            """);

        parser.parse(file);

        CodeNode topic = findJobTopic("com/acme/send-email");
        assertThat(topic).isNotNull();
        assertThat(topic.getProperties().get("topic")).isEqualTo("com/acme/send-email");
    }

    // -----------------------------------------------------------------------
    // Graph topology assertions
    // -----------------------------------------------------------------------

    @Test
    void sameTopicSharedBetweenProducerAndConsumer() throws IOException {
        Path consumer = writeJava("Consumer.java", """
            package com.example;
            @org.apache.sling.event.jobs.consumer.JobConsumer(slingJobTopics = "shared/topic")
            public class Consumer {}
            """);
        Path producer = writeJava("Producer.java", """
            package com.example;
            public class Producer {
                private org.apache.sling.event.jobs.JobManager jobManager;
                void go() { jobManager.addJob("shared/topic", null); }
            }
            """);

        parser.parse(consumer);
        parser.parse(producer);

        // Only one SLING_JOB node for the shared topic
        List<CodeNode> jobs = graphService.getAllNodes().stream()
            .filter(n -> n.getType() == NodeType.SLING_JOB)
            .toList();
        assertThat(jobs).hasSize(1);

        CodeNode topicNode = jobs.get(0);
        Set<CodeNode> processors = graphService.getInboundRelatedNodes(topicNode, RelationshipType.PROCESSES);
        Set<CodeNode> producers2  = graphService.getInboundRelatedNodes(topicNode, RelationshipType.PRODUCES);
        assertThat(processors).isNotEmpty();
        assertThat(producers2).isNotEmpty();
    }

    @Test
    void nonJobFileIsIgnoredQuickly() throws IOException {
        Path file = writeJava("RandomService.java", """
            package com.example;
            public class RandomService { void doWork() {} }
            """);

        parser.parse(file);

        // No SLING_JOB nodes should be created
        assertThat(graphService.getAllNodes().stream()
            .filter(n -> n.getType() == NodeType.SLING_JOB)
            .toList()).isEmpty();
    }

    @Test
    void parseAllScansDirectory() throws IOException {
        writeJava("sub/AConsumer.java", """
            package com.example;
            @org.apache.sling.event.jobs.consumer.JobConsumer(slingJobTopics = "dir/topic")
            public class AConsumer {}
            """);

        parser.parseAll(tempDir);

        assertThat(graphService.getAllNodes().stream()
            .filter(n -> n.getType() == NodeType.SLING_JOB)
            .toList()).hasSize(1);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private Path writeJava(String relativePath, String source) throws IOException {
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        return file;
    }

    private CodeNode findJobTopic(String topic) {
        return graphService.getAllNodes().stream()
            .filter(n -> n.getType() == NodeType.SLING_JOB && topic.equals(n.getName()))
            .findFirst()
            .orElse(null);
    }
}
