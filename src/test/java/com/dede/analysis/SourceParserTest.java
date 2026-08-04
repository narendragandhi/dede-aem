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

        // Assert - 4 nodes: Pkg + Class + Method + called method (println from CALLS tracking)
        assertThat(graphService.getNodeCount()).isEqualTo(4);
        
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

    /**
     * loadProfiles() always runs on every real scan (CLI, Docker, Maven plugin) and
     * always clears activeMappings first, then looked up profiles via a filesystem
     * path only -- no classpath fallback, unlike loadDefaultProfile(). Since real
     * consumers never run from dede-java's own source checkout (the only place
     * "profiles/aem.json" exists as a real file), every real invocation silently
     * loaded zero mappings here, breaking the @Model/@Component/@Reference/
     * @SlingServletResourceTypes relationship-building this method drives -- with
     * only a WARN log to notice by. This profile only exists as a classpath
     * resource (src/test/resources/profiles/classpath-only-test-profile.json), not
     * on the filesystem anywhere, so the filesystem lookup is guaranteed to miss and
     * this exercises the classpath fallback specifically.
     */
    @Test
    void loadProfilesFallsBackToClasspathWhenNotOnFilesystem(@TempDir Path tempDir) throws IOException {
        GraphService graphService = createService();
        SourceParser parser = new SourceParser(graphService);

        parser.loadProfiles(new String[]{"classpath-only-test-profile"});

        Path sourceFile = tempDir.resolve("Foo.java");
        Files.writeString(sourceFile, """
                package com.example;

                public @interface TestAnnotation { String value(); }
                """);
        Path annotatedFile = tempDir.resolve("Annotated.java");
        Files.writeString(annotatedFile, """
                package com.example;

                @TestAnnotation(value = "myResourceType")
                public class Annotated {
                }
                """);

        parser.parse(annotatedFile);

        CodeNode resourceTypeNode = graphService.findNodeById("test:myResourceType")
            .orElseThrow(() -> new AssertionError(
                "Expected node created by the classpath-loaded profile mapping was missing -- "
                    + "loadProfiles() likely fell back to filesystem-only lookup again"));
        assertThat(resourceTypeNode.getType()).isEqualTo(NodeType.JCR_RESOURCE_TYPE);
    }
}
