package com.dede.runtime;

import com.dede.domain.GraphService;
import com.dede.domain.model.CodeNode;
import com.dede.domain.model.ResourceTypes;
import com.dede.domain.model.RuntimeMetrics;
import com.dede.domain.model.RelationshipType;
import com.dede.runtime.RequestLogReader.RequestRecord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Attributes HTTP requests to project code without method-level instrumentation:
 * <ul>
 *   <li>path-registered servlets ({@code @SlingServletPaths}) match the request path directly;</li>
 *   <li>{@code /content} requests are resolved through the project's content to the resource
 *       types rendered, then to the Sling Models and resource-type servlets bound to them.</li>
 * </ul>
 * Counts are <em>estimated</em> renders: a page request counts once for every model on the
 * page, whether or not a given model method ran. Writes {@code runtime.requests} on class nodes
 * and {@code runtime.renders} on resource-type nodes.
 */
public final class RequestAttribution {

    public static final String PROP_REQUESTS = RuntimeMetrics.REQUESTS;
    /** On resource-type nodes: estimated renders of that component. */
    public static final String PROP_RENDERS = RuntimeMetrics.RENDERS;

    private record PathServlet(String path, CodeNode servlet) {}

    private final int total;
    private int attributed;
    private final Map<String, Long> requestsByClass = new HashMap<>();       // class signature -> requests
    private final Map<String, Long> rendersByResourceType = new HashMap<>();
    private final Map<String, Long> unattributedByPath = new HashMap<>();
    private final Map<String, Set<String>> classesByResourceType = new TreeMap<>();
    /** Resource type -> its sling:resourceSuperType (project components only). */
    private final Map<String, String> superTypeOf = new HashMap<>();
    private static final int MAX_SUPERTYPE_DEPTH = 10;

    private RequestAttribution(int total) {
        this.total = total;
    }

    /** Selectors and extension a servlet registered for; empty = no constraint. */
    private record Registration(Set<String> selectors, Set<String> extensions) {
        boolean accepts(SlingUrl url) {
            if (!extensions.isEmpty() && (url.extension() == null || !extensions.contains(url.extension()))) return false;
            return selectors.isEmpty() || url.selectors().containsAll(selectors);
        }
    }

    /** A request URL decomposed the way Sling does: resource path, selectors, extension. */
    record SlingUrl(String resourcePath, List<String> selectors, String extension) {
        static SlingUrl parse(String url) {
            String resourcePath = RequestAttribution.resourcePath(url);
            String rest = url.substring(resourcePath.length());
            int suffix = rest.indexOf('/');
            if (suffix >= 0) rest = rest.substring(0, suffix);
            List<String> parts = new ArrayList<>(List.of(rest.split("\\.")));
            parts.removeIf(String::isEmpty);
            if (parts.isEmpty()) return new SlingUrl(resourcePath, List.of(), null);
            String extension = parts.remove(parts.size() - 1);
            return new SlingUrl(resourcePath, parts, extension);
        }
    }

    public static RequestAttribution apply(List<RequestRecord> requests, ContentIndex content, GraphService graph) {
        RequestAttribution result = new RequestAttribution(requests.size());

        List<PathServlet> pathServlets = new ArrayList<>();
        Map<String, CodeNode> classById = new HashMap<>();
        Map<String, Registration> registrations = new HashMap<>();
        // Resource type -> classes bound to it, through every way the graph records a binding.
        Map<String, Set<String>> resourceTypeServlets = new HashMap<>();
        Map<String, List<CodeNode>> implementers = implementersBySimpleName(graph);
        for (CodeNode node : graph.getAllNodes()) {
            String id = node.getId();
            if (id.startsWith("path:")) {
                for (CodeNode servlet : graph.getInboundRelatedNodes(node, RelationshipType.HANDLES_PATH)) {
                    pathServlets.add(new PathServlet(node.getName(), servlet));
                }
            } else if (ResourceTypes.isResourceType(node)) {
                String resourceType = node.getName();
                for (CodeNode parent : graph.getRelatedNodes(node, RelationshipType.EXTENDS)) {
                    if (parent.getType() == node.getType()) result.superTypeOf.putIfAbsent(resourceType, parent.getName());
                }
                String declaredSuper = node.getProperties().get("sling:resourceSuperType");
                if (declaredSuper != null) result.superTypeOf.putIfAbsent(resourceType, declaredSuper);
                Set<String> bound = result.classesByResourceType.computeIfAbsent(resourceType, k -> new LinkedHashSet<>());
                // @Model(resourceType=...) -> INSTANTIATED_BY
                for (CodeNode cls : graph.getInboundRelatedNodes(node, RelationshipType.INSTANTIATED_BY)) {
                    bind(cls, bound, classById);
                }
                // @SlingServletResourceTypes -> HANDLES_RESOURCE_TYPE (selector/extension-constrained)
                for (CodeNode cls : graph.getInboundRelatedNodes(node, RelationshipType.HANDLES_RESOURCE_TYPE)) {
                    if (bind(cls, bound, classById)) {
                        resourceTypeServlets.computeIfAbsent(resourceType, k -> new LinkedHashSet<>()).add(cls.getSignature());
                        registrations.computeIfAbsent(cls.getSignature(), k -> registration(graph, cls));
                    }
                }
                // component folder -> HTL template -> data-sly-use class. HTL usually names the
                // model interface (data-sly-use="...ImageList"); the code that runs is its
                // implementation, so bind the implementing classes when there are any.
                for (CodeNode htl : graph.getRelatedNodes(node, RelationshipType.DEFINES)) {
                    for (CodeNode cls : graph.getRelatedNodes(htl, RelationshipType.USES)) {
                        List<CodeNode> impls = implementers.getOrDefault(simpleName(cls.getSignature()), List.of());
                        if (impls.isEmpty()) {
                            bind(cls, bound, classById);
                        } else {
                            impls.forEach(impl -> bind(impl, bound, classById));
                        }
                    }
                }
                if (bound.isEmpty()) result.classesByResourceType.remove(resourceType);
            }
        }

        for (RequestRecord request : requests) {
            String path = request.path();
            SlingUrl url = SlingUrl.parse(path);
            // A vanity URL (/promo, /promo.html) renders the page that declares it.
            String vanityPage = content.pageForVanityPath(url.resourcePath());
            if (vanityPage != null && content.resourceTypesFor(url.resourcePath()).isEmpty()) {
                url = new SlingUrl(vanityPage, url.selectors(), url.extension() != null ? url.extension() : "html");
            }
            // A shortened URL (/us/en.html for /content/site/us/en) when it maps to exactly one page.
            String fullPage = vanityPage == null ? content.pageForShortenedPath(url.resourcePath()) : null;
            if (fullPage != null && content.resourceTypesFor(url.resourcePath()).isEmpty()) {
                url = new SlingUrl(fullPage, url.selectors(), url.extension());
            }
            Set<String> classes = new LinkedHashSet<>();
            for (PathServlet ps : pathServlets) {
                if (path.equals(ps.path()) || path.startsWith(ps.path() + ".") || path.startsWith(ps.path() + "/")) {
                    classes.add(ps.servlet().getSignature());
                    classById.put(ps.servlet().getSignature(), ps.servlet());
                }
            }
            // If a resource-type servlet registered for the requested resource's own type accepts
            // this request, Sling dispatches to that servlet instead of rendering the HTL -- so the
            // components on the page don't render.
            String primaryType = content.primaryResourceType(url.resourcePath());
            Set<String> handledBy = new LinkedHashSet<>();
            if (primaryType != null) {
                for (String servlet : result.servletsFor(primaryType, resourceTypeServlets)) {
                    if (registrations.get(servlet).accepts(url)) handledBy.add(servlet);
                }
            }
            if (!handledBy.isEmpty()) {
                classes.addAll(handledBy);
            } else {
                for (String resourceType : content.resourceTypesFor(url.resourcePath())) {
                    result.rendersByResourceType.merge(resourceType, 1L, Long::sum);
                    Set<String> servlets = result.servletsFor(resourceType, resourceTypeServlets);
                    for (String cls : result.classesFor(resourceType)) {
                        // Servlets only ever serve requests matching their registration.
                        if (!servlets.contains(cls)) classes.add(cls);
                    }
                }
            }
            if (classes.isEmpty()) {
                result.unattributedByPath.merge(path, 1L, Long::sum);
            } else {
                result.attributed++;
                classes.forEach(c -> result.requestsByClass.merge(c, 1L, Long::sum));
            }
        }

        result.requestsByClass.forEach((signature, count) -> {
            CodeNode node = classById.get(signature);
            if (node != null) RuntimeMetrics.set(node, RuntimeMetrics.REQUESTS, count);
        });
        for (CodeNode node : graph.getAllNodes()) {
            if (ResourceTypes.isResourceType(node)) {
                Long renders = result.rendersByResourceType.get(node.getName());
                if (renders != null) RuntimeMetrics.set(node, RuntimeMetrics.RENDERS, renders);
            }
        }
        return result;
    }

    /**
     * Project classes by the simple name of each interface they implement (or declare as a
     * Sling Model adapter). The graph records implemented interfaces by the name as written in
     * source -- usually the simple name -- so that is the join key.
     */
    private static Map<String, List<CodeNode>> implementersBySimpleName(GraphService graph) {
        Map<String, List<CodeNode>> byName = new HashMap<>();
        for (CodeNode node : graph.getAllNodes()) {
            if (!node.getId().startsWith("class:") || node.getFilePath() == null) continue;
            for (CodeNode iface : graph.getRelatedNodes(node, RelationshipType.IMPLEMENTS)) {
                List<CodeNode> list = byName.computeIfAbsent(simpleName(iface.getName()), k -> new ArrayList<>());
                if (!list.contains(node)) list.add(node);
            }
        }
        return byName;
    }

    private static String simpleName(String name) {
        return name.substring(name.lastIndexOf('.') + 1);
    }

    private static boolean bind(CodeNode cls, Set<String> bound, Map<String, CodeNode> classById) {
        if (!cls.getId().startsWith("class:")) return false;
        bound.add(cls.getSignature());
        classById.putIfAbsent(cls.getSignature(), cls);
        return true;
    }

    private static Registration registration(GraphService graph, CodeNode servlet) {
        Set<String> selectors = new LinkedHashSet<>();
        for (CodeNode n : graph.getRelatedNodes(servlet, RelationshipType.HANDLES_SELECTOR)) selectors.add(n.getName());
        Set<String> extensions = new LinkedHashSet<>();
        for (CodeNode n : graph.getRelatedNodes(servlet, RelationshipType.HANDLES_EXTENSION)) {
            // The aem profile maps @SlingServletResourceTypes(methods=...) onto HANDLES_EXTENSION too,
            // as "method:GET" nodes; only "ext:" nodes are extensions.
            if (n.getId().startsWith("ext:")) extensions.add(n.getName());
        }
        return new Registration(selectors, extensions);
    }

    /** {@code type}, then its supertypes, nearest first. */
    List<String> superTypeChain(String type) {
        List<String> chain = new ArrayList<>();
        for (String t = type; t != null && chain.size() < MAX_SUPERTYPE_DEPTH && !chain.contains(t); t = superTypeOf.get(t)) {
            chain.add(t);
        }
        return chain;
    }

    /**
     * Project classes that run when {@code type} renders: its own, or -- for a component with no
     * script or model of its own -- those of the nearest supertype that has some. Sling falls back
     * to the supertype's script, and the Sling Models implementation picker walks supertypes too.
     */
    Set<String> classesFor(String type) {
        for (String t : superTypeChain(type)) {
            Set<String> classes = classesByResourceType.get(t);
            if (classes != null) return classes;
        }
        return Set.of();
    }

    /** Servlets registered for {@code type} or any supertype: Sling servlet resolution walks them. */
    private Set<String> servletsFor(String type, Map<String, Set<String>> resourceTypeServlets) {
        Set<String> servlets = new LinkedHashSet<>();
        for (String t : superTypeChain(type)) servlets.addAll(resourceTypeServlets.getOrDefault(t, Set.of()));
        return servlets;
    }

    /**
     * The resource path of a Sling request URL: everything before the first dot of the last
     * segment that has one ({@code /content/a/b.teaser.html/suffix} -> {@code /content/a/b}).
     */
    static String resourcePath(String url) {
        int slash = 0;
        while (true) {
            int nextSlash = url.indexOf('/', slash + 1);
            String segment = nextSlash < 0 ? url.substring(slash) : url.substring(slash, nextSlash);
            int dot = segment.indexOf('.');
            if (dot >= 0) return url.substring(0, slash + dot);
            if (nextSlash < 0) return url;
            slash = nextSlash;
        }
    }

    public int total() { return total; }
    public int attributed() { return attributed; }
    public Long requestsFor(String classSignature) { return requestsByClass.get(classSignature); }

    /** Resource types bound to project models/servlets that no request rendered in the window. */
    public List<String> resourceTypesNeverRendered() {
        // A supertype renders whenever a subtype that inherits from it does.
        Set<String> rendered = new java.util.HashSet<>();
        rendersByResourceType.keySet().forEach(t -> rendered.addAll(superTypeChain(t)));
        return classesByResourceType.keySet().stream().filter(rt -> !rendered.contains(rt)).toList();
    }

    private static List<Map.Entry<String, Long>> top(Map<String, Long> counts, int limit) {
        return counts.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
            .limit(limit).toList();
    }

    /** The "requests" block of the JSON report. */
    public Map<String, Object> summary(int limit) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", total);
        summary.put("attributed", attributed);
        summary.put("note", "counts are estimated renders: a page request counts once for each model on the page. "
            + "AEM's logs and spans only see requests that reach AEM: on publish, pages the dispatcher or CDN served "
            + "from cache are not counted, so these are cache misses, not page views.");
        summary.put("byClass", top(requestsByClass, limit).stream()
            .map(e -> Map.of("class", e.getKey(), "requests", e.getValue())).toList());
        summary.put("byResourceType", top(rendersByResourceType, limit).stream()
            .map(e -> Map.of("resourceType", e.getKey(), "renders", e.getValue())).toList());
        summary.put("resourceTypesNeverRendered", resourceTypesNeverRendered());
        summary.put("topUnattributedPaths", top(unattributedByPath, limit).stream()
            .map(e -> Map.of("path", e.getKey(), "requests", e.getValue())).toList());
        return summary;
    }

    public List<Map.Entry<String, Long>> hotClasses(int limit) {
        return top(requestsByClass, limit);
    }
}
