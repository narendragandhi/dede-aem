package com.dede.discovery;

import com.dede.domain.GraphAnalyzer;
import com.dede.domain.GraphExporter;
import com.dede.domain.GraphRepository;
import com.dede.domain.GraphService;
import com.dede.domain.model.CodeNode;
import com.dede.domain.model.NodeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OsgiConfigParser Tests")
class OsgiConfigParserTest {

    private OsgiConfigParser parser;
    private GraphService graphService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        GraphRepository repository = new GraphRepository();
        GraphAnalyzer analyzer = new GraphAnalyzer(repository);
        GraphExporter exporter = new GraphExporter(repository);
        graphService = new GraphService(repository, analyzer, exporter);
        parser = new OsgiConfigParser(graphService);
    }

    @Nested
    @DisplayName("CFG.JSON Parsing")
    class CfgJsonParsing {

        @Test
        @DisplayName("Should parse simple .cfg.json configuration")
        void shouldParseSimpleCfgJson() throws IOException {
            // Create config directory structure
            Path configDir = tempDir.resolve("ui.config/src/main/content/jcr_root/apps/myproject/config");
            Files.createDirectories(configDir);

            // Create a simple config file
            Path configFile = configDir.resolve("org.apache.sling.commons.log.LogManager.cfg.json");
            Files.writeString(configFile, """
                {
                    "org.apache.sling.commons.log.level": "info",
                    "org.apache.sling.commons.log.file": "logs/error.log",
                    "org.apache.sling.commons.log.maxFileSize": "10MB"
                }
                """);

            CodeNode node = parser.parseConfigFile(configFile, null);

            assertThat(node).isNotNull();
            assertThat(node.getType()).isEqualTo(NodeType.OSGI_CONFIG);
            assertThat(node.getProperties().get("pid")).isEqualTo("org.apache.sling.commons.log.LogManager");
            assertThat(node.getProperties().get("config.org.apache.sling.commons.log.level")).isEqualTo("info");
            assertThat(node.getProperties().get("format")).isEqualTo("cfg.json");
        }

        @Test
        @DisplayName("Should parse factory .cfg.json configuration with tilde")
        void shouldParseFactoryCfgJsonWithTilde() throws IOException {
            Path configDir = tempDir.resolve("ui.config/src/main/content/jcr_root/apps/myproject/config");
            Files.createDirectories(configDir);

            Path configFile = configDir.resolve("org.apache.sling.commons.log.LogManager.factory.config~mylogger.cfg.json");
            Files.writeString(configFile, """
                {
                    "org.apache.sling.commons.log.names": ["com.myproject"],
                    "org.apache.sling.commons.log.level": "debug"
                }
                """);

            CodeNode node = parser.parseConfigFile(configFile, null);

            assertThat(node).isNotNull();
            assertThat(node.getType()).isEqualTo(NodeType.OSGI_CONFIG_FACTORY);
            assertThat(node.getProperties().get("factoryPid")).isEqualTo("org.apache.sling.commons.log.LogManager.factory.config");
            assertThat(node.getProperties().get("factoryId")).isEqualTo("mylogger");
            assertThat(node.getProperties().get("config.org.apache.sling.commons.log.names")).isEqualTo("com.myproject");
        }

        @Test
        @DisplayName("Should parse factory .cfg.json configuration with dash")
        void shouldParseFactoryCfgJsonWithDash() throws IOException {
            Path configDir = tempDir.resolve("ui.config/src/main/content/jcr_root/apps/myproject/config");
            Files.createDirectories(configDir);

            Path configFile = configDir.resolve("org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended-myproject.cfg.json");
            Files.writeString(configFile, """
                {
                    "user.mapping": [
                        "com.myproject.core:reader=myproject-reader"
                    ]
                }
                """);

            CodeNode node = parser.parseConfigFile(configFile, null);

            assertThat(node).isNotNull();
            assertThat(node.getType()).isEqualTo(NodeType.OSGI_CONFIG_FACTORY);
            assertThat(node.getProperties().get("factoryPid")).isEqualTo("org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended");
            assertThat(node.getProperties().get("factoryId")).isEqualTo("myproject");
        }

        @Test
        @DisplayName("Should handle array properties")
        void shouldHandleArrayProperties() throws IOException {
            Path configDir = tempDir.resolve("ui.config/src/main/content/jcr_root/apps/myproject/config");
            Files.createDirectories(configDir);

            Path configFile = configDir.resolve("com.adobe.granite.auth.saml.SamlAuthenticationHandler.cfg.json");
            Files.writeString(configFile, """
                {
                    "path": ["/content", "/apps"],
                    "service.ranking": 100,
                    "createUser": true
                }
                """);

            CodeNode node = parser.parseConfigFile(configFile, null);

            assertThat(node).isNotNull();
            assertThat(node.getProperties().get("config.path")).isEqualTo("/content,/apps");
            assertThat(node.getProperties().get("config.service.ranking")).isEqualTo("100");
            assertThat(node.getProperties().get("config.createUser")).isEqualTo("true");
        }
    }

    @Nested
    @DisplayName("Run Mode Detection")
    class RunModeDetection {

        @Test
        @DisplayName("Should detect author run mode")
        void shouldDetectAuthorRunMode() throws IOException {
            Path configDir = tempDir.resolve("ui.config/src/main/content/jcr_root/apps/myproject/config.author");
            Files.createDirectories(configDir);

            Path configFile = configDir.resolve("com.day.cq.dam.core.impl.servlet.HealthCheckServlet.cfg.json");
            Files.writeString(configFile, """
                {
                    "sling.servlet.paths": "/bin/healthcheck"
                }
                """);

            CodeNode node = parser.parseConfigFile(configFile, "author");

            assertThat(node).isNotNull();
            assertThat(node.getProperties().get("runMode")).isEqualTo("author");
            assertThat(node.getId()).contains(":author");
        }

        @Test
        @DisplayName("Should detect compound run modes")
        void shouldDetectCompoundRunModes() throws IOException {
            Path configDir = tempDir.resolve("ui.config/src/main/content/jcr_root/apps/myproject/config.author.dev");
            Files.createDirectories(configDir);

            Path configFile = configDir.resolve("com.day.cq.commons.impl.ExternalizerImpl.cfg.json");
            Files.writeString(configFile, """
                {
                    "externalizer.domains": ["local http://localhost:4502"]
                }
                """);

            CodeNode node = parser.parseConfigFile(configFile, "author.dev");

            assertThat(node).isNotNull();
            assertThat(node.getProperties().get("runMode")).isEqualTo("author.dev");
        }

        @Test
        @DisplayName("Should scan all config folders in project")
        void shouldScanAllConfigFolders() throws IOException {
            // Create multiple config folders
            Path baseConfig = tempDir.resolve("ui.config/src/main/content/jcr_root/apps/myproject/config");
            Path authorConfig = tempDir.resolve("ui.config/src/main/content/jcr_root/apps/myproject/config.author");
            Path publishConfig = tempDir.resolve("ui.config/src/main/content/jcr_root/apps/myproject/config.publish");

            Files.createDirectories(baseConfig);
            Files.createDirectories(authorConfig);
            Files.createDirectories(publishConfig);

            Files.writeString(baseConfig.resolve("com.example.BaseConfig.cfg.json"), """
                {"setting": "base"}
                """);
            Files.writeString(authorConfig.resolve("com.example.AuthorConfig.cfg.json"), """
                {"setting": "author"}
                """);
            Files.writeString(publishConfig.resolve("com.example.PublishConfig.cfg.json"), """
                {"setting": "publish"}
                """);

            List<CodeNode> nodes = parser.scanConfigurations(tempDir);

            assertThat(nodes).hasSize(3);
            assertThat(nodes).extracting(n -> n.getProperties().get("runMode"))
                            .containsExactlyInAnyOrder(null, "author", "publish");
        }
    }

    @Nested
    @DisplayName("Felix Config Parsing")
    class FelixConfigParsing {

        @Test
        @DisplayName("Should parse simple .config file")
        void shouldParseSimpleConfigFile() throws IOException {
            Path configDir = tempDir.resolve("ui.config/src/main/content/jcr_root/apps/myproject/config");
            Files.createDirectories(configDir);

            Path configFile = configDir.resolve("org.apache.sling.jcr.repoinit.RepositoryInitializer.config");
            Files.writeString(configFile, """
                scripts=["create path /content/mysite"]
                """);

            CodeNode node = parser.parseConfigFile(configFile, null);

            assertThat(node).isNotNull();
            assertThat(node.getType()).isEqualTo(NodeType.OSGI_CONFIG);
            assertThat(node.getProperties().get("pid")).isEqualTo("org.apache.sling.jcr.repoinit.RepositoryInitializer");
            assertThat(node.getProperties().get("format")).isEqualTo("config");
        }

        @Test
        @DisplayName("Should parse factory .config file")
        void shouldParseFactoryConfigFile() throws IOException {
            Path configDir = tempDir.resolve("ui.config/src/main/content/jcr_root/apps/myproject/config");
            Files.createDirectories(configDir);

            Path configFile = configDir.resolve("org.apache.sling.jcr.repoinit.RepositoryInitializer-myproject.config");
            Files.writeString(configFile, """
                scripts=["create service user myproject-reader"]
                """);

            CodeNode node = parser.parseConfigFile(configFile, null);

            assertThat(node).isNotNull();
            assertThat(node.getType()).isEqualTo(NodeType.OSGI_CONFIG_FACTORY);
            assertThat(node.getProperties().get("factoryPid")).isEqualTo("org.apache.sling.jcr.repoinit.RepositoryInitializer");
            assertThat(node.getProperties().get("factoryId")).isEqualTo("myproject");
        }
    }

    @Nested
    @DisplayName("Service User Mapping Detection")
    class ServiceUserMappingDetection {

        @Test
        @DisplayName("Should identify service user mapping configuration")
        void shouldIdentifyServiceUserMapping() throws IOException {
            Path configDir = tempDir.resolve("ui.config/src/main/content/jcr_root/apps/myproject/config");
            Files.createDirectories(configDir);

            Path configFile = configDir.resolve("org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended-myproject.cfg.json");
            Files.writeString(configFile, """
                {
                    "user.mapping": [
                        "com.myproject.core:reader=myproject-reader",
                        "com.myproject.core:writer=myproject-writer"
                    ]
                }
                """);

            CodeNode node = parser.parseConfigFile(configFile, null);

            assertThat(node).isNotNull();
            assertThat(parser.isServiceUserMapping(node)).isTrue();
        }

        @Test
        @DisplayName("Should not identify non-service-user configs as service user mapping")
        void shouldNotIdentifyNonServiceUserConfigs() throws IOException {
            Path configDir = tempDir.resolve("ui.config/src/main/content/jcr_root/apps/myproject/config");
            Files.createDirectories(configDir);

            Path configFile = configDir.resolve("org.apache.sling.commons.log.LogManager.cfg.json");
            Files.writeString(configFile, """
                {
                    "org.apache.sling.commons.log.level": "info"
                }
                """);

            CodeNode node = parser.parseConfigFile(configFile, null);

            assertThat(node).isNotNull();
            assertThat(parser.isServiceUserMapping(node)).isFalse();
        }
    }

    @Nested
    @DisplayName("Target Service PID")
    class TargetServicePid {

        @Test
        @DisplayName("Should return PID for regular config")
        void shouldReturnPidForRegularConfig() throws IOException {
            Path configDir = tempDir.resolve("ui.config/src/main/content/jcr_root/apps/myproject/config");
            Files.createDirectories(configDir);

            Path configFile = configDir.resolve("com.example.MyService.cfg.json");
            Files.writeString(configFile, """
                {"enabled": true}
                """);

            CodeNode node = parser.parseConfigFile(configFile, null);

            assertThat(parser.getTargetServicePid(node)).isEqualTo("com.example.MyService");
        }

        @Test
        @DisplayName("Should return factory PID for factory config")
        void shouldReturnFactoryPidForFactoryConfig() throws IOException {
            Path configDir = tempDir.resolve("ui.config/src/main/content/jcr_root/apps/myproject/config");
            Files.createDirectories(configDir);

            Path configFile = configDir.resolve("com.example.MyFactory~instance1.cfg.json");
            Files.writeString(configFile, """
                {"name": "instance1"}
                """);

            CodeNode node = parser.parseConfigFile(configFile, null);

            assertThat(parser.getTargetServicePid(node)).isEqualTo("com.example.MyFactory");
        }
    }

    @Nested
    @DisplayName("Graph Integration")
    class GraphIntegration {

        @Test
        @DisplayName("Should add parsed configs to graph")
        void shouldAddParsedConfigsToGraph() throws IOException {
            Path configDir = tempDir.resolve("ui.config/src/main/content/jcr_root/apps/myproject/config");
            Files.createDirectories(configDir);

            Files.writeString(configDir.resolve("com.example.Config1.cfg.json"), """
                {"enabled": true}
                """);
            Files.writeString(configDir.resolve("com.example.Config2.cfg.json"), """
                {"enabled": false}
                """);

            List<CodeNode> nodes = parser.scanConfigurations(tempDir);

            assertThat(nodes).hasSize(2);
            assertThat(graphService.getNodeCount()).isEqualTo(2);
            assertThat(graphService.findNodeById("osgiconfig:com.example.Config1")).isPresent();
            assertThat(graphService.findNodeById("osgiconfig:com.example.Config2")).isPresent();
        }
    }
}
