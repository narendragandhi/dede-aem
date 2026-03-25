package com.dede.discovery;

import com.dede.domain.GraphService;
import com.dede.domain.model.CodeNode;
import com.dede.domain.model.NodeType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Parses OSGi configuration files (.cfg.json, .config, .xml) from AEM projects.
 * Creates OSGI_CONFIG and OSGI_CONFIG_FACTORY nodes in the graph.
 *
 * Supports:
 * - Sling OSGi Config Admin JSON format (.cfg.json)
 * - Apache Felix Config Admin format (.config)
 * - Run mode detection from folder names (config.author, config.prod, etc.)
 */
@Component
public class OsgiConfigParser {

    private static final Logger log = LoggerFactory.getLogger(OsgiConfigParser.class);

    private final GraphService graphService;
    private final ObjectMapper objectMapper;

    // Pattern for factory config: <pid>~<factoryId>.cfg.json or <pid>-<factoryId>.cfg.json
    private static final Pattern FACTORY_CONFIG_PATTERN = Pattern.compile("^(.+)[~-](.+)\\.cfg\\.json$");
    // Pattern for regular config: <pid>.cfg.json
    private static final Pattern CONFIG_PATTERN = Pattern.compile("^(.+)\\.cfg\\.json$");
    // Pattern for .config files (Apache Felix format)
    private static final Pattern FELIX_CONFIG_PATTERN = Pattern.compile("^(.+)\\.config$");
    // Pattern for run mode folders: config, config.<runmode>, config.<runmode1>.<runmode2>
    private static final Pattern RUNMODE_FOLDER_PATTERN = Pattern.compile("config(?:\\.([\\w.]+))?");

    public OsgiConfigParser(GraphService graphService) {
        this.graphService = graphService;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Scans a project directory for OSGi configurations in /apps/** /config* folders.
     */
    public List<CodeNode> scanConfigurations(Path projectRoot) {
        List<CodeNode> configNodes = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(projectRoot)) {
            paths.filter(Files::isDirectory)
                 .filter(this::isConfigFolder)
                 .forEach(configDir -> {
                     String runMode = extractRunMode(configDir);
                     try (Stream<Path> configFiles = Files.list(configDir)) {
                         configFiles.filter(Files::isRegularFile)
                                   .filter(this::isConfigFile)
                                   .forEach(configFile -> {
                                       CodeNode node = parseConfigFile(configFile, runMode);
                                       if (node != null) {
                                           configNodes.add(node);
                                       }
                                   });
                     } catch (IOException e) {
                         log.warn("Failed to list config files in {}: {}", configDir, e.getMessage());
                     }
                 });
        } catch (IOException e) {
            log.error("Failed to scan configurations in {}: {}", projectRoot, e.getMessage());
        }

        return configNodes;
    }

    /**
     * Parses a single OSGi configuration file.
     */
    public CodeNode parseConfigFile(Path configFile, String runMode) {
        String fileName = configFile.getFileName().toString();

        if (fileName.endsWith(".cfg.json")) {
            return parseCfgJson(configFile, runMode);
        } else if (fileName.endsWith(".config")) {
            return parseFelixConfig(configFile, runMode);
        }

        return null;
    }

    /**
     * Parses Sling OSGi Config Admin JSON format (.cfg.json).
     */
    private CodeNode parseCfgJson(Path configFile, String runMode) {
        String fileName = configFile.getFileName().toString();

        try {
            JsonNode root = objectMapper.readTree(configFile.toFile());

            // Determine if factory config or regular config
            Matcher factoryMatcher = FACTORY_CONFIG_PATTERN.matcher(fileName);
            Matcher configMatcher = CONFIG_PATTERN.matcher(fileName);

            String pid;
            String factoryPid = null;
            String factoryId = null;
            NodeType nodeType;

            if (factoryMatcher.matches()) {
                // Factory configuration
                factoryPid = factoryMatcher.group(1);
                factoryId = factoryMatcher.group(2);
                pid = factoryPid + "~" + factoryId;
                nodeType = NodeType.OSGI_CONFIG_FACTORY;
            } else if (configMatcher.matches()) {
                pid = configMatcher.group(1);
                nodeType = NodeType.OSGI_CONFIG;
            } else {
                log.warn("Unexpected config file name format: {}", fileName);
                return null;
            }

            String nodeId = "osgiconfig:" + pid + (runMode != null ? ":" + runMode : "");
            CodeNode node = new CodeNode(
                nodeId,
                pid,
                nodeType,
                pid,
                configFile.toString()
            );

            // Add properties
            node.getProperties().put("pid", pid);
            if (factoryPid != null) {
                node.getProperties().put("factoryPid", factoryPid);
                node.getProperties().put("factoryId", factoryId);
            }
            if (runMode != null) {
                node.getProperties().put("runMode", runMode);
            }
            node.getProperties().put("format", "cfg.json");

            // Extract all configuration properties
            Map<String, String> configProperties = extractProperties(root);
            configProperties.forEach((key, value) ->
                node.getProperties().put("config." + key, value)
            );

            graphService.addNode(node);
            log.debug("Parsed OSGi config: {} (runMode: {})", pid, runMode);

            return node;

        } catch (IOException e) {
            log.warn("Failed to parse .cfg.json file {}: {}", configFile, e.getMessage());
            return null;
        }
    }

    /**
     * Parses Apache Felix Config Admin format (.config).
     */
    private CodeNode parseFelixConfig(Path configFile, String runMode) {
        String fileName = configFile.getFileName().toString();
        Matcher matcher = FELIX_CONFIG_PATTERN.matcher(fileName);

        if (!matcher.matches()) {
            return null;
        }

        String pidPart = matcher.group(1);
        String pid;
        String factoryPid = null;
        String factoryId = null;
        NodeType nodeType;

        // Check for factory pattern in PID
        if (pidPart.contains("-")) {
            int lastDash = pidPart.lastIndexOf('-');
            factoryPid = pidPart.substring(0, lastDash);
            factoryId = pidPart.substring(lastDash + 1);
            pid = factoryPid + "~" + factoryId;
            nodeType = NodeType.OSGI_CONFIG_FACTORY;
        } else {
            pid = pidPart;
            nodeType = NodeType.OSGI_CONFIG;
        }

        try {
            String content = Files.readString(configFile);
            Map<String, String> properties = parseFelixConfigContent(content);

            String nodeId = "osgiconfig:" + pid + (runMode != null ? ":" + runMode : "");
            CodeNode node = new CodeNode(
                nodeId,
                pid,
                nodeType,
                pid,
                configFile.toString()
            );

            node.getProperties().put("pid", pid);
            if (factoryPid != null) {
                node.getProperties().put("factoryPid", factoryPid);
                node.getProperties().put("factoryId", factoryId);
            }
            if (runMode != null) {
                node.getProperties().put("runMode", runMode);
            }
            node.getProperties().put("format", "config");

            properties.forEach((key, value) ->
                node.getProperties().put("config." + key, value)
            );

            graphService.addNode(node);
            log.debug("Parsed Felix config: {} (runMode: {})", pid, runMode);

            return node;

        } catch (IOException e) {
            log.warn("Failed to parse .config file {}: {}", configFile, e.getMessage());
            return null;
        }
    }

    /**
     * Parses Apache Felix .config file content.
     * Format: key=type:value or key="string"
     */
    private Map<String, String> parseFelixConfigContent(String content) {
        Map<String, String> properties = new LinkedHashMap<>();

        // Simple line-by-line parsing
        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            int eqIndex = line.indexOf('=');
            if (eqIndex > 0) {
                String key = line.substring(0, eqIndex).trim();
                String value = line.substring(eqIndex + 1).trim();

                // Remove type prefixes like B"...", I"...", L"..."
                if (value.length() > 2 && value.charAt(1) == '"') {
                    value = value.substring(2, value.length() - 1);
                } else if (value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }

                // Handle arrays: [...], remove brackets
                if (value.startsWith("[") && value.endsWith("]")) {
                    value = value.substring(1, value.length() - 1);
                }

                properties.put(key, value);
            }
        }

        return properties;
    }

    /**
     * Extracts properties from JSON config.
     */
    private Map<String, String> extractProperties(JsonNode root) {
        Map<String, String> properties = new LinkedHashMap<>();

        root.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            JsonNode value = entry.getValue();

            if (value.isArray()) {
                List<String> values = new ArrayList<>();
                value.forEach(v -> values.add(v.asText()));
                properties.put(key, String.join(",", values));
            } else if (value.isObject()) {
                // Nested objects - flatten with dot notation
                extractNestedProperties(key, value, properties);
            } else {
                properties.put(key, value.asText());
            }
        });

        return properties;
    }

    private void extractNestedProperties(String prefix, JsonNode node, Map<String, String> properties) {
        node.fields().forEachRemaining(entry -> {
            String key = prefix + "." + entry.getKey();
            JsonNode value = entry.getValue();

            if (value.isObject()) {
                extractNestedProperties(key, value, properties);
            } else if (value.isArray()) {
                List<String> values = new ArrayList<>();
                value.forEach(v -> values.add(v.asText()));
                properties.put(key, String.join(",", values));
            } else {
                properties.put(key, value.asText());
            }
        });
    }

    /**
     * Checks if a directory is an OSGi config folder.
     */
    private boolean isConfigFolder(Path dir) {
        String folderName = dir.getFileName().toString();
        return RUNMODE_FOLDER_PATTERN.matcher(folderName).matches() &&
               isUnderAppsOrConf(dir);
    }

    /**
     * Checks if path is under /apps or /conf folders (AEM convention).
     */
    private boolean isUnderAppsOrConf(Path path) {
        String pathStr = path.toString().replace('\\', '/');
        return pathStr.contains("/apps/") ||
               pathStr.contains("/conf/") ||
               pathStr.contains("/ui.apps/") ||
               pathStr.contains("/ui.config/");
    }

    /**
     * Checks if a file is a valid OSGi config file.
     */
    private boolean isConfigFile(Path file) {
        String fileName = file.getFileName().toString();
        return fileName.endsWith(".cfg.json") || fileName.endsWith(".config");
    }

    /**
     * Extracts run mode from config folder path.
     * e.g., config.author.dev -> author.dev
     */
    private String extractRunMode(Path configDir) {
        String folderName = configDir.getFileName().toString();
        Matcher matcher = RUNMODE_FOLDER_PATTERN.matcher(folderName);

        if (matcher.matches() && matcher.group(1) != null) {
            return matcher.group(1);
        }

        return null; // Base config folder (no specific run mode)
    }

    /**
     * Gets the service PID that this configuration targets.
     * Used for linking configs to their target services.
     */
    public String getTargetServicePid(CodeNode configNode) {
        if (configNode.getType() == NodeType.OSGI_CONFIG_FACTORY) {
            return configNode.getProperties().get("factoryPid");
        }
        return configNode.getProperties().get("pid");
    }

    /**
     * Checks if this is a service user mapping configuration.
     */
    public boolean isServiceUserMapping(CodeNode configNode) {
        String pid = configNode.getProperties().get("pid");
        if (pid == null) {
            pid = configNode.getProperties().get("factoryPid");
        }
        return pid != null &&
               pid.startsWith("org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl");
    }
}
