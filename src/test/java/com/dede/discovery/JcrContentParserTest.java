package com.dede.discovery;

import com.dede.domain.*;
import com.dede.domain.model.CodeNode;
import com.dede.domain.model.NodeType;
import com.dede.domain.model.RelationshipType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for JcrContentParser covering .content.xml parsing and proxy component detection.
 */
@DisplayName("JCR Content Parser Tests")
class JcrContentParserTest {

    private GraphService graphService;
    private JcrContentParser parser;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        GraphRepository repository = new GraphRepository();
        GraphAnalyzer analyzer = new GraphAnalyzer(repository);
        GraphExporter exporter = new GraphExporter(repository);
        graphService = new GraphService(repository, analyzer, exporter);
        parser = new JcrContentParser(graphService);
    }

    @Nested
    @DisplayName("Basic Parsing")
    class BasicParsing {

        @Test
        @DisplayName("Should support .content.xml files")
        void shouldSupportContentXmlFiles() {
            assertThat(parser.supports(Path.of("test/.content.xml"))).isTrue();
            assertThat(parser.supports(Path.of(".content.xml"))).isTrue();
        }

        @Test
        @DisplayName("Should not support other XML files")
        void shouldNotSupportOtherFiles() {
            assertThat(parser.supports(Path.of("pom.xml"))).isFalse();
            assertThat(parser.supports(Path.of("content.xml"))).isFalse();
            assertThat(parser.supports(Path.of("_cq_dialog.xml"))).isFalse();
        }

        @Test
        @DisplayName("Should parse basic content with resourceType")
        void shouldParseBasicContent() throws IOException {
            String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <jcr:root xmlns:jcr="http://www.jcp.org/jcr/1.0"
                          xmlns:sling="http://sling.apache.org/jcr/sling/1.0"
                          jcr:primaryType="nt:unstructured"
                          sling:resourceType="wknd/components/text"/>
                """;

            Path file = tempDir.resolve(".content.xml");
            Files.writeString(file, xml);

            parser.parse(file);

            List<CodeNode> resourceTypes = graphService.findNodesByType(NodeType.JCR_RESOURCE_TYPE);
            assertThat(resourceTypes).isNotEmpty();
            assertThat(resourceTypes.stream().anyMatch(r -> r.getName().equals("wknd/components/text"))).isTrue();
        }

        @Test
        @DisplayName("Should handle malformed XML gracefully")
        void shouldHandleMalformedXml() throws IOException {
            String xml = """
                <?xml version="1.0"?>
                <broken><unclosed>
                """;

            Path file = tempDir.resolve(".content.xml");
            Files.writeString(file, xml);

            // Should not throw
            parser.parse(file);

            // Just don't crash
            assertThat(graphService.getNodeCount()).isZero();
        }
    }

    @Nested
    @DisplayName("Proxy Component Detection")
    class ProxyComponentDetection {

        @Test
        @DisplayName("Should detect proxy component with sling:resourceSuperType")
        void shouldDetectProxyComponent() throws IOException {
            // Simulate apps/wknd/components/button/.content.xml
            Path appsDir = tempDir.resolve("jcr_root/apps/wknd/components/button");
            Files.createDirectories(appsDir);

            String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <jcr:root xmlns:jcr="http://www.jcp.org/jcr/1.0"
                          xmlns:cq="http://www.day.com/jcr/cq/1.0"
                          xmlns:sling="http://sling.apache.org/jcr/sling/1.0"
                          jcr:primaryType="cq:Component"
                          jcr:title="Button"
                          sling:resourceSuperType="core/wcm/components/button/v2/button"
                          componentGroup="WKND - Content"/>
                """;

            Path file = appsDir.resolve(".content.xml");
            Files.writeString(file, xml);

            parser.parse(file);

            List<CodeNode> resourceTypes = graphService.findNodesByType(NodeType.JCR_RESOURCE_TYPE);

            // Should have at least the proxy and super type
            assertThat(resourceTypes.size()).isGreaterThanOrEqualTo(2);

            // Find the proxy component
            CodeNode proxy = resourceTypes.stream()
                .filter(r -> "true".equals(r.getProperties().get("isProxy")))
                .findFirst()
                .orElse(null);

            assertThat(proxy).isNotNull();
            assertThat(proxy.getProperties().get("sling:resourceSuperType"))
                .isEqualTo("core/wcm/components/button/v2/button");
        }

        @Test
        @DisplayName("Should create EXTENDS relationship for proxy")
        void shouldCreateExtendsRelationship() throws IOException {
            Path appsDir = tempDir.resolve("jcr_root/apps/mysite/components/carousel");
            Files.createDirectories(appsDir);

            String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <jcr:root xmlns:jcr="http://www.jcp.org/jcr/1.0"
                          xmlns:cq="http://www.day.com/jcr/cq/1.0"
                          xmlns:sling="http://sling.apache.org/jcr/sling/1.0"
                          jcr:primaryType="cq:Component"
                          sling:resourceSuperType="core/wcm/components/carousel/v1/carousel"/>
                """;

            Path file = appsDir.resolve(".content.xml");
            Files.writeString(file, xml);

            parser.parse(file);

            // Find the proxy component
            List<CodeNode> resourceTypes = graphService.findNodesByType(NodeType.JCR_RESOURCE_TYPE);
            CodeNode proxy = resourceTypes.stream()
                .filter(r -> "true".equals(r.getProperties().get("isProxy")))
                .findFirst()
                .orElse(null);

            assertThat(proxy).isNotNull();

            // Should have EXTENDS relationship to super type
            Set<CodeNode> superTypes = graphService.getRelatedNodes(proxy, RelationshipType.EXTENDS);
            assertThat(superTypes).isNotEmpty();
            assertThat(superTypes.stream().anyMatch(s -> s.getId().contains("carousel/v1/carousel"))).isTrue();
        }

        @Test
        @DisplayName("Should not mark non-component content as proxy")
        void shouldNotMarkNonComponentAsProxy() throws IOException {
            String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <jcr:root xmlns:jcr="http://www.jcp.org/jcr/1.0"
                          xmlns:sling="http://sling.apache.org/jcr/sling/1.0"
                          jcr:primaryType="nt:unstructured"
                          sling:resourceType="wknd/components/text"
                          sling:resourceSuperType="core/wcm/components/text/v2/text"/>
                """;

            Path file = tempDir.resolve(".content.xml");
            Files.writeString(file, xml);

            parser.parse(file);

            // Content instances should not be proxies (only cq:Component definitions)
            List<CodeNode> resourceTypes = graphService.findNodesByType(NodeType.JCR_RESOURCE_TYPE);
            long proxyCount = resourceTypes.stream()
                .filter(r -> "true".equals(r.getProperties().get("isProxy")))
                .count();

            assertThat(proxyCount).isZero();
        }
    }

    @Nested
    @DisplayName("Security - Secret Sanitization")
    class SecuritySanitization {

        @Test
        @DisplayName("Should mask sensitive properties")
        void shouldMaskSensitiveProperties() throws IOException {
            String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <jcr:root xmlns:jcr="http://www.jcp.org/jcr/1.0"
                          xmlns:sling="http://sling.apache.org/jcr/sling/1.0"
                          jcr:primaryType="nt:unstructured"
                          sling:resourceType="myapp/components/config"
                          password="supersecret123"
                          apikey="abc-xyz-123"
                          normalProp="visible"/>
                """;

            Path file = tempDir.resolve(".content.xml");
            Files.writeString(file, xml);

            parser.parse(file);

            List<CodeNode> components = graphService.findNodesByType(NodeType.JCR_COMPONENT);
            assertThat(components).isNotEmpty();

            CodeNode component = components.get(0);
            assertThat(component.getProperties().get("password")).isEqualTo("[MASKED]");
            assertThat(component.getProperties().get("apikey")).isEqualTo("[MASKED]");
            assertThat(component.getProperties().get("normalProp")).isEqualTo("visible");
        }
    }

    @Nested
    @DisplayName("XXE Prevention")
    class XxePrevention {

        @Test
        @DisplayName("Should not process external entities")
        void shouldNotProcessExternalEntities() throws IOException {
            // Create a file that an XXE attack would try to read
            Path secretFile = tempDir.resolve("secret.txt");
            Files.writeString(secretFile, "TOP SECRET DATA");

            // XXE attack payload
            String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE foo [
                  <!ENTITY xxe SYSTEM "file://%s">
                ]>
                <jcr:root xmlns:jcr="http://www.jcp.org/jcr/1.0"
                          xmlns:sling="http://sling.apache.org/jcr/sling/1.0"
                          jcr:primaryType="nt:unstructured"
                          sling:resourceType="&xxe;"/>
                """.formatted(secretFile.toAbsolutePath());

            Path file = tempDir.resolve(".content.xml");
            Files.writeString(file, xml);

            // Should not throw and should not expose file contents
            parser.parse(file);

            // Verify the secret was not extracted
            List<CodeNode> allNodes = graphService.findNodesByType(NodeType.JCR_RESOURCE_TYPE);
            boolean containsSecret = allNodes.stream()
                .anyMatch(n -> n.getId().contains("TOP SECRET") ||
                               n.getName().contains("TOP SECRET") ||
                               (n.getSignature() != null && n.getSignature().contains("TOP SECRET")));

            assertThat(containsSecret).isFalse();
        }
    }
}
