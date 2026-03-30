package com.dede.discovery;

import com.dede.domain.GraphAnalyzer;
import com.dede.domain.GraphExporter;
import com.dede.domain.GraphRepository;
import com.dede.domain.GraphService;
import com.dede.domain.model.CodeNode;
import com.dede.domain.model.NodeType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the enhanced DispatcherParser.
 */
class DispatcherParserTest {

    private GraphService graphService;
    private DispatcherParser parser;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        GraphRepository repository = new GraphRepository();
        GraphAnalyzer analyzer = new GraphAnalyzer(repository);
        GraphExporter exporter = new GraphExporter(repository);
        graphService = new GraphService(repository, analyzer, exporter);
        parser = new DispatcherParser(graphService);
    }

    @Nested
    @DisplayName("Filter Rule Parsing")
    class FilterRuleParsing {

        @Test
        @DisplayName("Should parse allow and deny filter rules")
        void shouldParseFilterRules() throws IOException {
            String dispatcherConfig = """
                /filter {
                    /0001 { /type "deny" /url "*" }
                    /0002 { /type "allow" /url "/content/*" }
                    /0003 { /type "allow" /url "/etc.clientlibs/*" }
                    /0004 { /type "deny" /url "/bin/*" }
                }
                """;

            Path configFile = tempDir.resolve("filter.any");
            Files.writeString(configFile, dispatcherConfig);

            parser.parse(configFile);

            List<CodeNode> filterNodes = graphService.findNodesByType(NodeType.DISPATCHER_FILTER);
            assertEquals(4, filterNodes.size());

            // Verify deny rule
            CodeNode denyRule = filterNodes.stream()
                .filter(n -> n.getId().contains(":0001"))
                .findFirst().orElse(null);
            assertNotNull(denyRule);
            assertEquals("deny", denyRule.getProperties().get("filterType"));
        }

        @Test
        @DisplayName("Should parse filter rules with selectors and extensions")
        void shouldParseFilterWithSelectorsExtensions() throws IOException {
            String dispatcherConfig = """
                /filter {
                    /0001 { /type "deny" /url "*" }
                    /0002 {
                        /type "allow"
                        /url "/content/*"
                        /extension "(html|json)"
                        /selectors "(mobile|desktop)"
                        /method "GET"
                    }
                }
                """;

            Path configFile = tempDir.resolve("filter.any");
            Files.writeString(configFile, dispatcherConfig);

            parser.parse(configFile);

            List<CodeNode> filterNodes = graphService.findNodesByType(NodeType.DISPATCHER_FILTER);
            CodeNode allowRule = filterNodes.stream()
                .filter(n -> n.getId().contains(":0002"))
                .findFirst().orElse(null);

            assertNotNull(allowRule);
            assertEquals("(html|json)", allowRule.getProperties().get("extension"));
            assertEquals("(mobile|desktop)", allowRule.getProperties().get("selectors"));
            assertEquals("GET", allowRule.getProperties().get("method"));
        }
    }

    @Nested
    @DisplayName("Security Detection")
    class SecurityDetection {

        @Test
        @DisplayName("Should detect missing filter section")
        void shouldDetectMissingFilterSection() throws IOException {
            String dispatcherConfig = """
                /cache {
                    /docroot "/var/cache"
                }
                """;

            Path configFile = tempDir.resolve("dispatcher.any");
            Files.writeString(configFile, dispatcherConfig);

            parser.parse(configFile);

            List<DispatcherParser.DispatcherSecurityIssue> issues = parser.getSecurityIssues();
            assertTrue(issues.stream().anyMatch(i -> i.ruleId.equals("DISP-001")));
        }

        @Test
        @DisplayName("Should detect missing default deny")
        void shouldDetectMissingDefaultDeny() throws IOException {
            String dispatcherConfig = """
                /filter {
                    /0001 { /type "allow" /url "/content/*" }
                    /0002 { /type "allow" /url "/etc.clientlibs/*" }
                }
                """;

            Path configFile = tempDir.resolve("filter.any");
            Files.writeString(configFile, dispatcherConfig);

            parser.parse(configFile);

            List<DispatcherParser.DispatcherSecurityIssue> issues = parser.getSecurityIssues();
            assertTrue(issues.stream().anyMatch(i -> i.ruleId.equals("DISP-002")),
                "Should detect missing default deny rule");
        }

        @Test
        @DisplayName("Should not flag when default deny exists")
        void shouldNotFlagWhenDefaultDenyExists() throws IOException {
            String dispatcherConfig = """
                /filter {
                    /0001 { /type "deny" /url "*" }
                    /0002 { /type "allow" /url "/content/*" }
                }
                """;

            Path configFile = tempDir.resolve("filter.any");
            Files.writeString(configFile, dispatcherConfig);

            parser.parse(configFile);

            List<DispatcherParser.DispatcherSecurityIssue> issues = parser.getSecurityIssues();
            assertFalse(issues.stream().anyMatch(i -> i.ruleId.equals("DISP-002")),
                "Should not flag when default deny exists");
        }

        @Test
        @DisplayName("Should detect dangerous extensions")
        void shouldDetectDangerousExtensions() throws IOException {
            String dispatcherConfig = """
                /filter {
                    /0001 { /type "deny" /url "*" }
                    /0002 { /type "allow" /url "/content/*" /extension "json" }
                }
                """;

            Path configFile = tempDir.resolve("filter.any");
            Files.writeString(configFile, dispatcherConfig);

            parser.parse(configFile);

            List<DispatcherParser.DispatcherSecurityIssue> issues = parser.getSecurityIssues();
            assertTrue(issues.stream().anyMatch(i -> i.ruleId.equals("DISP-003")),
                "Should detect dangerous extension 'json'");
        }

        @Test
        @DisplayName("Should detect dangerous paths")
        void shouldDetectDangerousPaths() throws IOException {
            String dispatcherConfig = """
                /filter {
                    /0001 { /type "deny" /url "*" }
                    /0002 { /type "allow" /url "/crx/*" }
                }
                """;

            Path configFile = tempDir.resolve("filter.any");
            Files.writeString(configFile, dispatcherConfig);

            parser.parse(configFile);

            List<DispatcherParser.DispatcherSecurityIssue> issues = parser.getSecurityIssues();
            assertTrue(issues.stream().anyMatch(i -> i.ruleId.equals("DISP-005")),
                "Should detect dangerous path '/crx'");
        }

        @Test
        @DisplayName("Should create vulnerability nodes for security issues")
        void shouldCreateVulnerabilityNodes() throws IOException {
            String dispatcherConfig = """
                /filter {
                    /0001 { /type "allow" /url "/crx/*" }
                }
                """;

            Path configFile = tempDir.resolve("filter.any");
            Files.writeString(configFile, dispatcherConfig);

            parser.parse(configFile);

            List<CodeNode> vulnerabilities = graphService.findNodesByType(NodeType.VULNERABILITY);
            assertFalse(vulnerabilities.isEmpty(), "Should create vulnerability nodes");
            assertTrue(vulnerabilities.stream()
                .anyMatch(v -> v.getProperties().get("category").equals("DISPATCHER_SECURITY")));
        }
    }

    @Nested
    @DisplayName("Farm Parsing")
    class FarmParsing {

        @Test
        @DisplayName("Should parse farm definitions")
        void shouldParseFarmDefinitions() throws IOException {
            String dispatcherConfig = """
                /farms {
                    /publish-farm {
                        /filter {
                            /0001 { /type "deny" /url "*" }
                            /0002 { /type "allow" /url "/content/*" }
                        }
                    }
                }
                """;

            Path configFile = tempDir.resolve("dispatcher.any");
            Files.writeString(configFile, dispatcherConfig);

            parser.parse(configFile);

            List<CodeNode> filterNodes = graphService.findNodesByType(NodeType.DISPATCHER_FILTER);
            assertTrue(filterNodes.stream().anyMatch(n -> n.getId().contains("publish-farm")));
        }
    }

    @Nested
    @DisplayName("Cache and Rewrite Parsing")
    class CacheAndRewriteParsing {

        @Test
        @DisplayName("Should parse cache section")
        void shouldParseCacheSection() throws IOException {
            String dispatcherConfig = """
                /filter {
                    /0001 { /type "deny" /url "*" }
                }
                /cache {
                    /docroot "/var/cache"
                    /rules {
                        /0001 { /type "deny" /glob "*" }
                        /0002 { /type "allow" /glob "*.html" }
                    }
                }
                """;

            Path configFile = tempDir.resolve("dispatcher.any");
            Files.writeString(configFile, dispatcherConfig);

            parser.parse(configFile);

            List<CodeNode> cacheNodes = graphService.findNodesByType(NodeType.CDN_RULE);
            assertTrue(cacheNodes.stream().anyMatch(n -> n.getName().equals("Cache Rules")));
        }

        @Test
        @DisplayName("Should parse cache invalidation")
        void shouldParseCacheInvalidation() throws IOException {
            String dispatcherConfig = """
                /filter {
                    /0001 { /type "deny" /url "*" }
                }
                /cache {
                    /invalidate {
                        /0001 { /glob "*.html" }
                    }
                }
                """;

            Path configFile = tempDir.resolve("dispatcher.any");
            Files.writeString(configFile, dispatcherConfig);

            parser.parse(configFile);

            List<CodeNode> cacheNodes = graphService.findNodesByType(NodeType.CDN_RULE);
            assertTrue(cacheNodes.stream().anyMatch(n -> n.getName().equals("Cache Invalidation")));
        }
    }

    @Nested
    @DisplayName("File Support")
    class FileSupport {

        @Test
        @DisplayName("Should support .any files")
        void shouldSupportAnyFiles() {
            assertTrue(parser.supports(Path.of("dispatcher.any")));
            assertTrue(parser.supports(Path.of("filter.any")));
            assertTrue(parser.supports(Path.of("custom-filters.any")));
        }

        @Test
        @DisplayName("Should not support non-dispatcher files")
        void shouldNotSupportNonDispatcherFiles() {
            assertFalse(parser.supports(Path.of("config.xml")));
            assertFalse(parser.supports(Path.of("application.yml")));
        }
    }
}
