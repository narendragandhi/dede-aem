package com.dede.discovery;

import com.dede.domain.*;
import com.dede.domain.model.CodeNode;
import com.dede.domain.model.NodeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SourceParserTest {

    private GraphService createService() {
        GraphRepository repo = new GraphRepository();
        GraphAnalyzer analyzer = new GraphAnalyzer(repo);
        GraphExporter exporter = new GraphExporter(repo);
        return new GraphService(repo, analyzer, exporter);
    }

    @Test
    void shouldParseSimpleClass(@TempDir Path tempDir) throws IOException {
        // Arrange
        GraphService graphService = createService();
        SourceParser parser = new SourceParser(graphService);

        Path sourceFile = tempDir.resolve("TestClass.java");
        String sourceCode = """
                package com.example;
                
                public class TestClass {
                    public void myMethod() {
                        System.out.println("Hello");
                    }
                }
                """;
        Files.writeString(sourceFile, sourceCode);

        // Act
        parser.parse(sourceFile);

        // Assert
        assertThat(graphService.getNodeCount()).isEqualTo(3); // Pkg + Class + Method
        
        // Verify Package
        CodeNode pkgNode = graphService.findNodeById("pkg:com.example").orElseThrow();
        assertThat(pkgNode.getType()).isEqualTo(NodeType.PACKAGE);

        // Verify Class
        CodeNode classNode = graphService.findNodeById("class:com.example.TestClass").orElseThrow();
        assertThat(classNode.getType()).isEqualTo(NodeType.CLASS);

        // Verify Method
        CodeNode methodNode = graphService.findNodeById("method:com.example.TestClass.myMethod()").orElseThrow();
        assertThat(methodNode.getType()).isEqualTo(NodeType.METHOD);
    }
}
