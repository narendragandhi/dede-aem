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
 * <p>The repository only holds sample or seed content: on a real site most pages are authored in
 * AEM. Those are added from the instance itself -- a content package exported from Package
 * Manager ({@link #addPackage}), or a QueryBuilder listing of every node with a resource type,
 * saved to a file or fetched live ({@link #addQueryBuilderHits}, {@link ContentSource}).
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
        String pagePath = jcrPath("/" + file.toString().replace('\\', '/'));
        if (pagePath == null) return;
        try (var in = Files.newInputStream(file)) {
            addDocument(pagePath, parse(in));
        } catch (Exception e) {
            log.debug("Content index: skipping {}: {}", file, e.getMessage());
        }
    }

    /** JCR path of a {@code .../jcr_root/content|conf/.../.content.xml} file, or null. */
    private static String jcrPath(String normalized) {
        int root = normalized.indexOf("/jcr_root/content/");
        if (root < 0) root = normalized.indexOf("/jcr_root/conf/");
        if (root < 0 || !normalized.endsWith("/.content.xml")) return null;
        return normalized.substring(root + "/jcr_root".length(), normalized.length() - "/.content.xml".length());
    }

    /**
     * Adds the {@code /content} and {@code /conf} documents of a content package (a zip from
     * Package Manager, or any FileVault package). Returns the number of documents read.
     */
    public int addPackage(Path zip) throws IOException {
        int added = 0;
        try (var in = new java.util.zip.ZipInputStream(Files.newInputStream(zip))) {
            java.util.zip.ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                String pagePath = entry.isDirectory() ? null : jcrPath("/" + entry.getName());
                if (pagePath == null) continue;
                try {
                    // parse() must not close the zip stream.
                    addDocument(pagePath, parse(new java.io.ByteArrayInputStream(in.readAllBytes())));
                    added++;
                } catch (Exception e) {
                    log.debug("Content index: skipping {} in {}: {}", entry.getName(), zip, e.getMessage());
                }
            }
        }
        lookups.clear();
        return added;
    }

    /**
     * Adds QueryBuilder hits: one per node, with {@code jcr:path}, {@code sling:resourceType} and
     * optionally {@code cq:template} / {@code fragmentVariationPath}. Page grouping comes from the
     * path: everything under {@code <page>/jcr:content} belongs to {@code <page>}. Returns the
     * number of nodes added.
     */
    public int addQueryBuilderHits(com.fasterxml.jackson.databind.JsonNode response) {
        int added = 0;
        for (var hit : response.path("hits")) {
            String path = hit.path("jcr:path").asText("");
            if (path.isEmpty()) continue;
            String page = pageOf(path);
            String resourceType = hit.path("sling:resourceType").asText("");
            if (!resourceType.isEmpty()) {
                resourceTypeByNode.put(path, resourceType);
                resourceTypesByPage.computeIfAbsent(page, k -> new LinkedHashSet<>()).add(resourceType);
                added++;
            }
            String template = hit.path("cq:template").asText("");
            if (template.startsWith("/conf/")) {
                includesByPage.computeIfAbsent(page, k -> new LinkedHashSet<>()).add(template + "/structure");
            }
            String fragment = hit.path("fragmentVariationPath").asText("");
            if (fragment.startsWith("/content/")) {
                includesByPage.computeIfAbsent(page, k -> new LinkedHashSet<>()).add(fragment);
            }
        }
        lookups.clear();
        return added;
    }

    /** The page a node belongs to: the path before {@code /jcr:content}, or the node itself. */
    static String pageOf(String nodePath) {
        int content = nodePath.indexOf("/jcr:content");
        return content > 0 ? nodePath.substring(0, content) : nodePath;
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
