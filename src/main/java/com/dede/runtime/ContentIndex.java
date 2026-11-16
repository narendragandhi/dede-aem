package com.dede.runtime;

import com.dede.discovery.ScanExclusions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Index of the project's own content ({@code jcr_root/content/**}/.content.xml): which
 * resource types each page and component node uses. Lets a request URL be mapped to the
 * components (and so the Sling Models and servlets) that render it.
 *
 * <p>A page renders more than its own content: the structure of its editable template
 * ({@code cq:template} -> {@code <template>/structure}, indexed from {@code jcr_root/conf}) and
 * any experience fragments it embeds ({@code fragmentVariationPath}). Both are followed.
 *
 * <p>Only content in the project is known. Production pages authored in AEM (not in the code
 * repository) are not -- their requests stay unattributed unless a path servlet handles them.
 */
public final class ContentIndex {

    private static final Logger log = LoggerFactory.getLogger(ContentIndex.class);

    /** JCR node path -> its sling:resourceType, for every node in indexed content that has one. */
    private final Map<String, String> resourceTypeByNode = new HashMap<>();
    /** Page path -> every resource type used anywhere on that page. */
    private final Map<String, Set<String>> resourceTypesByPage = new HashMap<>();
    /** Page path -> paths it pulls in at render time (template structure, experience fragments). */
    private final Map<String, Set<String>> includesByPage = new HashMap<>();
    private static final int MAX_INCLUDE_DEPTH = 5;

    public static ContentIndex build(Path projectRoot) {
        ContentIndex index = new ContentIndex();
        try (Stream<Path> files = Files.walk(projectRoot)) {
            files.filter(p -> p.getFileName().toString().equals(".content.xml"))
                .filter(p -> !ScanExclusions.isExcluded(projectRoot, p))
                .forEach(index::add);
        } catch (IOException e) {
            log.warn("Content index: could not walk {}: {}", projectRoot, e.getMessage());
        }
        return index;
    }

    /** For tests: index one file whose JCR path is given. */
    static ContentIndex of(String pagePath, String contentXml) throws Exception {
        ContentIndex index = new ContentIndex();
        index.addDocument(pagePath, parse(new java.io.ByteArrayInputStream(contentXml.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        return index;
    }

    private void add(Path file) {
        String normalized = file.toString().replace('\\', '/');
        int root = normalized.indexOf("/jcr_root/content/");
        if (root < 0) root = normalized.indexOf("/jcr_root/conf/");
        if (root < 0) return;
        String pagePath = normalized.substring(root + "/jcr_root".length(), normalized.length() - "/.content.xml".length());
        try (var in = Files.newInputStream(file)) {
            addDocument(pagePath, parse(in));
        } catch (Exception e) {
            log.debug("Content index: skipping {}: {}", file, e.getMessage());
        }
    }

    private static Element parse(java.io.InputStream in) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(in).getDocumentElement();
    }

    private void addDocument(String pagePath, Element root) {
        Set<String> onPage = new LinkedHashSet<>();
        Set<String> includes = new LinkedHashSet<>();
        walk(root, pagePath, onPage, includes);
        // Several content packages can define the same page (WKND's ui.content and
        // ui.content.sample both ship /content/wknd/us/en); AEM merges them on install, so union.
        if (!onPage.isEmpty()) {
            resourceTypesByPage.computeIfAbsent(pagePath, k -> new LinkedHashSet<>()).addAll(onPage);
        }
        if (!includes.isEmpty()) {
            includesByPage.computeIfAbsent(pagePath, k -> new LinkedHashSet<>()).addAll(includes);
        }
    }

    private void walk(Element element, String nodePath, Set<String> onPage, Set<String> includes) {
        String resourceType = element.getAttribute("sling:resourceType");
        if (!resourceType.isEmpty()) {
            resourceTypeByNode.put(nodePath, resourceType);
            onPage.add(resourceType);
        }
        String template = element.getAttribute("cq:template");
        if (template.startsWith("/conf/")) {
            includes.add(template + "/structure");
        }
        String fragment = element.getAttribute("fragmentVariationPath");
        if (fragment.startsWith("/content/")) {
            includes.add(fragment);
        }
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element childElement) {
                walk(childElement, nodePath + "/" + childElement.getTagName(), onPage, includes);
            }
        }
    }

    /**
     * Resource types rendered by a request for {@code resourcePath}: every type on the page for
     * a page request, the node's subtree for a component request (e.g. a {@code .model.json}
     * on {@code /content/site/en/jcr:content/root/teaser}), empty when the path isn't indexed.
     */
    public Set<String> resourceTypesFor(String resourcePath) {
        // Request logs repeat the same few paths thousands of times; the subtree scan is linear.
        return lookups.computeIfAbsent(resourcePath, this::lookup);
    }

    private final Map<String, Set<String>> lookups = new HashMap<>();

    private Set<String> lookup(String resourcePath) {
        if (resourceTypesByPage.containsKey(resourcePath)) {
            Set<String> rendered = new LinkedHashSet<>();
            collectPage(resourcePath, rendered, new java.util.HashSet<>(), 0);
            return rendered;
        }
        Set<String> subtree = new LinkedHashSet<>();
        String prefix = resourcePath + "/";
        resourceTypeByNode.forEach((path, type) -> {
            if (path.equals(resourcePath) || path.startsWith(prefix)) subtree.add(type);
        });
        return subtree;
    }

    /** A page's own resource types plus those of its template structure and embedded fragments. */
    private void collectPage(String pagePath, Set<String> out, Set<String> visited, int depth) {
        if (depth > MAX_INCLUDE_DEPTH || !visited.add(pagePath)) return;
        out.addAll(resourceTypesByPage.getOrDefault(pagePath, Set.of()));
        for (String include : includesByPage.getOrDefault(pagePath, Set.of())) {
            collectPage(include, out, visited, depth + 1);
        }
    }

    /**
     * The resource type Sling resolves for a request to {@code resourcePath}: a page's
     * {@code jcr:content} type, or the node's own type; null when not indexed.
     */
    public String primaryResourceType(String resourcePath) {
        String pageContent = resourceTypeByNode.get(resourcePath + "/jcr:content");
        return pageContent != null ? pageContent : resourceTypeByNode.get(resourcePath);
    }

    public int pageCount() {
        return resourceTypesByPage.size();
    }
}
