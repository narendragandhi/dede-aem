package com.dede.security;

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
 * Detects XSS vulnerabilities in HTL (Sightly) templates.
 *
 * <h3>HTL XSS primer</h3>
 * HTL auto-escapes output based on a <em>display context</em>.
 * The default context is {@code text} (safe for most use), but developers
 * can override it with {@code @ context='...'}.  Dangerous overrides:
 * <ul>
 *   <li>{@code unsafe} — disables all escaping (CRITICAL)</li>
 *   <li>{@code html}   — only HTML-encodes, not safe for attribute values or JS</li>
 *   <li>{@code scriptString} / {@code styleString} — must come from trusted sources only</li>
 * </ul>
 *
 * Additionally, any {@code ${...}} expression that reads request parameters
 * without an explicit safe context is flagged.
 */
@Component
public class XssDetector {

    private static final Logger log = LoggerFactory.getLogger(XssDetector.class);

    // context='unsafe' — always critical
    private static final Pattern UNSAFE_CONTEXT = Pattern.compile(
        "@\\s*context\\s*=\\s*['\"]unsafe['\"]",
        Pattern.CASE_INSENSITIVE);

    // context='html' — risky when used with user input
    private static final Pattern HTML_CONTEXT = Pattern.compile(
        "@\\s*context\\s*=\\s*['\"]html['\"]",
        Pattern.CASE_INSENSITIVE);

    // context='scriptString' or 'styleString' — needs review
    private static final Pattern SCRIPT_STYLE_CONTEXT = Pattern.compile(
        "@\\s*context\\s*=\\s*['\"](?:scriptString|styleString|scriptComment|styleComment)['\"]",
        Pattern.CASE_INSENSITIVE);

    // ${...} expression that references request parameters or query strings
    private static final Pattern USER_INPUT_EXPR = Pattern.compile(
        "\\$\\{[^}]*(?:request\\.getParameter|currentPage\\.properties\\[|wcmmode|" +
        "properties\\.get|param\\.|pageProperties)[^}]*\\}",
        Pattern.CASE_INSENSITIVE);

    // ${...} expressions in unsafe attribute positions (href=, onclick=, style=)
    private static final Pattern ATTR_XSS = Pattern.compile(
        "(?:href|src|action|formaction|onclick|onload|onerror|style)\\s*=\\s*[\"']?\\$\\{",
        Pattern.CASE_INSENSITIVE);

    // data-sly-attribute with expression (potential DOM injection)
    private static final Pattern SLY_ATTRIBUTE_EXPR = Pattern.compile(
        "data-sly-attribute(?:\\.[a-zA-Z0-9_-]+)?\\s*=\\s*\"\\$\\{[^}]+\\}\"");

    public ScanResult scan(Path projectRoot) {
        List<XssFinding> findings = new ArrayList<>();

        if (!Files.exists(projectRoot)) return new ScanResult(findings);

        try (Stream<Path> paths = Files.walk(projectRoot)) {
            paths.filter(Files::isRegularFile)
                 .filter(p -> p.toString().endsWith(".html"))
                 .forEach(htlFile -> findings.addAll(scanFile(htlFile, projectRoot)));
        } catch (IOException e) {
            log.error("Error scanning for XSS in {}", projectRoot, e);
        }

        log.info("XSS scan complete: {} findings in {}", findings.size(), projectRoot);
        return new ScanResult(findings);
    }

    private List<XssFinding> scanFile(Path htlFile, Path projectRoot) {
        List<XssFinding> findings = new ArrayList<>();
        String relPath;
        try {
            relPath = projectRoot.relativize(htlFile).toString();
        } catch (IllegalArgumentException e) {
            relPath = htlFile.toString();
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(htlFile);
        } catch (IOException e) {
            log.debug("Could not read {}", htlFile, e);
            return findings;
        }

        for (int i = 0; i < lines.size(); i++) {
            int lineNo = i + 1;
            String line = lines.get(i);

            // CRITICAL: context='unsafe'
            if (UNSAFE_CONTEXT.matcher(line).find()) {
                findings.add(new XssFinding(relPath, lineNo, Severity.CRITICAL,
                    FindingType.UNSAFE_CONTEXT,
                    "context='unsafe' disables all HTL auto-escaping on this expression.",
                    "Remove 'unsafe' context. Use the default (text) or a safe context like 'attribute'."
                ));
            }

            // HIGH: context='html'
            if (HTML_CONTEXT.matcher(line).find()) {
                // Escalate to CRITICAL if user input is also present
                boolean hasUserInput = USER_INPUT_EXPR.matcher(line).find();
                Severity sev = hasUserInput ? Severity.CRITICAL : Severity.HIGH;
                findings.add(new XssFinding(relPath, lineNo, sev,
                    FindingType.HTML_CONTEXT,
                    "context='html' only HTML-encodes output. It is NOT safe for attribute values " +
                    "or JavaScript contexts" + (hasUserInput ? " and this line reads user input." : "."),
                    "Use context='text' for text content, context='attribute' for attributes."
                ));
            }

            // MEDIUM: scriptString / styleString context
            if (SCRIPT_STYLE_CONTEXT.matcher(line).find()) {
                findings.add(new XssFinding(relPath, lineNo, Severity.MEDIUM,
                    FindingType.SCRIPT_STYLE_CONTEXT,
                    "Script/style context used. Value must originate from a trusted, author-controlled source.",
                    "Ensure this expression never reflects user-supplied or URL-derived data."
                ));
            }

            // HIGH: href/src/action/event-handler attribute with inline expression
            Matcher attrMatcher = ATTR_XSS.matcher(line);
            if (attrMatcher.find()) {
                String attr = attrMatcher.group().split("=")[0].trim();
                findings.add(new XssFinding(relPath, lineNo, Severity.HIGH,
                    FindingType.UNSAFE_ATTRIBUTE,
                    String.format("Expression used directly in '%s' attribute without explicit " +
                        "URI/attribute context.", attr),
                    String.format("Add @ context='uri' for href/src/action, or context='attribute' " +
                        "for '%s'.", attr)
                ));
            }

            // MEDIUM: data-sly-attribute with expression
            if (SLY_ATTRIBUTE_EXPR.matcher(line).find()
                    && !line.contains("context=")) {
                findings.add(new XssFinding(relPath, lineNo, Severity.MEDIUM,
                    FindingType.SLY_ATTRIBUTE_NO_CONTEXT,
                    "data-sly-attribute sets an attribute from an expression without explicit context.",
                    "Add @ context='attribute' or @ context='uri' as appropriate."
                ));
            }
        }

        return findings;
    }

    // --- Types ---

    public enum Severity { CRITICAL, HIGH, MEDIUM, LOW }

    public enum FindingType {
        UNSAFE_CONTEXT,
        HTML_CONTEXT,
        SCRIPT_STYLE_CONTEXT,
        UNSAFE_ATTRIBUTE,
        SLY_ATTRIBUTE_NO_CONTEXT
    }

    public record XssFinding(
        String filePath,
        int line,
        Severity severity,
        FindingType type,
        String message,
        String recommendation
    ) {}

    public record ScanResult(List<XssFinding> findings) {

        public long countBySeverity(Severity severity) {
            return findings.stream().filter(f -> f.severity() == severity).count();
        }

        public boolean hasCritical() {
            return findings.stream().anyMatch(f -> f.severity() == Severity.CRITICAL);
        }

        public Map<String, List<XssFinding>> byFile() {
            return findings.stream().collect(
                java.util.stream.Collectors.groupingBy(XssFinding::filePath));
        }
    }
}
