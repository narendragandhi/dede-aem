package com.dede.discovery;

import com.dede.domain.*;
import com.dede.domain.model.CodeNode;
import com.dede.domain.model.NodeType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for SlingHtlParser covering real-world HTL patterns.
 */
@DisplayName("Sling HTL Parser Tests")
class SlingHtlParserTest {

    private GraphService graphService;
    private SlingHtlParser parser;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        GraphRepository repository = new GraphRepository();
        GraphAnalyzer analyzer = new GraphAnalyzer(repository);
        GraphExporter exporter = new GraphExporter(repository);
        graphService = new GraphService(repository, analyzer, exporter);
        parser = new SlingHtlParser(graphService);
    }

    @Nested
    @DisplayName("data-sly-use Parsing")
    class DataSlyUseParsing {

        @Test
        @DisplayName("Should parse basic data-sly-use with Java class")
        void shouldParseBasicDataSlyUse() throws IOException {
            String htl = """
                <div data-sly-use.model="com.example.MyModel">
                    ${model.title}
                </div>
                """;

            Path file = tempDir.resolve("test.html");
            Files.writeString(file, htl);

            parser.parse(file);

            List<CodeNode> htlFiles = graphService.findNodesByType(NodeType.HTL_FILE);
            assertThat(htlFiles).hasSize(1);
        }

        @Test
        @DisplayName("Should parse data-sly-use with spaces around equals")
        void shouldParseWithSpaces() throws IOException {
            // Real WKND pattern
            String htl = """
                <sly data-sly-use.clientlib = "/libs/granite/sightly/templates/clientlib.html">
                    ${clientlib.js}
                </sly>
                """;

            Path file = tempDir.resolve("test.html");
            Files.writeString(file, htl);

            parser.parse(file);

            List<CodeNode> htlFiles = graphService.findNodesByType(NodeType.HTL_FILE);
            // Parser creates node for the source file AND referenced HTL file
            assertThat(htlFiles).isNotEmpty();
            assertThat(htlFiles.stream().anyMatch(n -> n.getFilePath() != null && n.getFilePath().contains("test.html"))).isTrue();
        }

        @Test
        @DisplayName("Should parse data-sly-use with expression syntax")
        void shouldParseExpressionSyntax() throws IOException {
            String htl = """
                <sly data-sly-use.templates="${'core/wcm/components/commons/v1/templates.html'}"/>
                """;

            Path file = tempDir.resolve("test.html");
            Files.writeString(file, htl);

            parser.parse(file);

            List<CodeNode> htlFiles = graphService.findNodesByType(NodeType.HTL_FILE);
            assertThat(htlFiles).hasSize(1);
        }

        @Test
        @DisplayName("Should parse data-sly-use without variable name")
        void shouldParseWithoutVariableName() throws IOException {
            String htl = """
                <div data-sly-use="com.example.Model"></div>
                """;

            Path file = tempDir.resolve("test.html");
            Files.writeString(file, htl);

            parser.parse(file);

            List<CodeNode> htlFiles = graphService.findNodesByType(NodeType.HTL_FILE);
            assertThat(htlFiles).hasSize(1);
        }
    }

    @Nested
    @DisplayName("data-sly-resource Parsing")
    class DataSlyResourceParsing {

        @Test
        @DisplayName("Should parse data-sly-resource with resourceType")
        void shouldParseResourceType() throws IOException {
            String htl = """
                <div data-sly-resource="${'header' @ resourceType='wknd/components/header'}"></div>
                """;

            Path file = tempDir.resolve("test.html");
            Files.writeString(file, htl);

            parser.parse(file);

            List<CodeNode> htlFiles = graphService.findNodesByType(NodeType.HTL_FILE);
            assertThat(htlFiles).hasSize(1);
        }
    }

    @Nested
    @DisplayName("data-sly-test/list/repeat Parsing")
    class ControlStructureParsing {

        @Test
        @DisplayName("Should handle data-sly-test")
        void shouldHandleDataSlyTest() throws IOException {
            String htl = """
                <div data-sly-test="${model.visible}">
                    <p>Content</p>
                </div>
                """;

            Path file = tempDir.resolve("test.html");
            Files.writeString(file, htl);

            parser.parse(file);

            List<CodeNode> htlFiles = graphService.findNodesByType(NodeType.HTL_FILE);
            assertThat(htlFiles).hasSize(1);

            // Should track complexity
            CodeNode htlNode = htlFiles.get(0);
            assertThat(htlNode.getProperties().get("complexity")).isNotNull();
        }

        @Test
        @DisplayName("Should handle data-sly-list")
        void shouldHandleDataSlyList() throws IOException {
            String htl = """
                <ul data-sly-list="${model.items}">
                    <li>${item.name}</li>
                </ul>
                """;

            Path file = tempDir.resolve("test.html");
            Files.writeString(file, htl);

            parser.parse(file);

            List<CodeNode> htlFiles = graphService.findNodesByType(NodeType.HTL_FILE);
            assertThat(htlFiles).hasSize(1);
        }

        @Test
        @DisplayName("Should handle data-sly-repeat")
        void shouldHandleDataSlyRepeat() throws IOException {
            String htl = """
                <div data-sly-repeat="${model.columns}">
                    ${item}
                </div>
                """;

            Path file = tempDir.resolve("test.html");
            Files.writeString(file, htl);

            parser.parse(file);

            List<CodeNode> htlFiles = graphService.findNodesByType(NodeType.HTL_FILE);
            assertThat(htlFiles).hasSize(1);
        }
    }

    @Nested
    @DisplayName("Expression Parsing")
    class ExpressionParsing {

        @Test
        @DisplayName("Should count HTL expressions")
        void shouldCountExpressions() throws IOException {
            String htl = """
                <div>
                    <h1>${properties.title}</h1>
                    <p>${properties.description}</p>
                    <span class="${model.cssClass}">${model.text}</span>
                </div>
                """;

            Path file = tempDir.resolve("test.html");
            Files.writeString(file, htl);

            parser.parse(file);

            List<CodeNode> htlFiles = graphService.findNodesByType(NodeType.HTL_FILE);
            assertThat(htlFiles).hasSize(1);

            CodeNode htlNode = htlFiles.get(0);
            String expressionCount = htlNode.getProperties().get("expressionCount");
            assertThat(Integer.parseInt(expressionCount)).isGreaterThanOrEqualTo(4);
        }

        @Test
        @DisplayName("Should handle complex nested expressions")
        void shouldHandleNestedExpressions() throws IOException {
            String htl = """
                <div data-sly-use.model="com.example.Model"
                     data-sly-test="${model.visible && model.enabled}">
                    <a href="${model.link @ context='uri'}"
                       class="${model.active ? 'active' : 'inactive'}">
                        ${model.title @ i18n, locale='en'}
                    </a>
                </div>
                """;

            Path file = tempDir.resolve("test.html");
            Files.writeString(file, htl);

            parser.parse(file);

            // Should not throw
            List<CodeNode> htlFiles = graphService.findNodesByType(NodeType.HTL_FILE);
            assertThat(htlFiles).hasSize(1);
        }
    }

    @Nested
    @DisplayName("Real WKND Patterns")
    class RealWkndPatterns {

        @Test
        @DisplayName("Should parse WKND byline.html pattern")
        void shouldParseBylinePattern() throws IOException {
            String htl = """
                <div data-sly-use.byline="com.adobe.aem.guides.wknd.core.models.Byline"
                     data-sly-test="${byline.empty}"
                     data-sly-resource="${'image' @ resourceType='core/wcm/components/image/v2/image'}"
                     class="cmp-byline">
                    <div class="cmp-byline__content">
                        <h2 class="cmp-byline__name">${byline.name}</h2>
                        <p class="cmp-byline__occupations"
                           data-sly-list.occupation="${byline.occupations}">
                            ${occupation}
                        </p>
                    </div>
                </div>
                """;

            Path file = tempDir.resolve("byline.html");
            Files.writeString(file, htl);

            parser.parse(file);

            List<CodeNode> htlFiles = graphService.findNodesByType(NodeType.HTL_FILE);
            assertThat(htlFiles).hasSize(1);

            CodeNode htlNode = htlFiles.get(0);
            // Should have decent complexity
            int complexity = Integer.parseInt(htlNode.getProperties().getOrDefault("complexity", "0"));
            assertThat(complexity).isGreaterThan(0);
        }

        @Test
        @DisplayName("Should parse clientlib include pattern")
        void shouldParseClientlibPattern() throws IOException {
            String htl = """
                <sly data-sly-use.clientlib="/libs/granite/sightly/templates/clientlib.html"
                     data-sly-call="${clientlib.css @ categories='wknd.base'}"/>
                <sly data-sly-use.clientlib="/libs/granite/sightly/templates/clientlib.html"
                     data-sly-call="${clientlib.js @ categories='wknd.base'}"/>
                """;

            Path file = tempDir.resolve("customheaderlibs.html");
            Files.writeString(file, htl);

            parser.parse(file);

            List<CodeNode> htlFiles = graphService.findNodesByType(NodeType.HTL_FILE);
            // Parser creates nodes for source file, referenced HTL, and template calls
            assertThat(htlFiles).isNotEmpty();
            assertThat(htlFiles.stream().anyMatch(n -> n.getFilePath() != null && n.getFilePath().contains("customheaderlibs.html"))).isTrue();
        }
    }
}
