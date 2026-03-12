package com.dede.analysis;

import com.dede.core.GraphService;
import com.dede.core.model.CodeNode;
import com.dede.core.model.NodeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SourceParserTest {

    @Test
    void shouldParseSimpleClass(@TempDir Path tempDir) throws IOException {
        // Arrange
        GraphService graphService = new GraphService();
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
