package com.dede.cloud;

import com.dede.cloud.ForbiddenApiScanner.ForbiddenApiViolation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Usage rules must fire on the anti-pattern, never on a plain import -- importing
 * ResourceResolver or PageManager is normal in nearly every AEM class.
 */
class UsagePatternDetectorTest {

    @TempDir
    Path dir;

    private ForbiddenApiScanner scanner;

    @BeforeEach
    void setUp() {
        ForbiddenApiCatalog catalog = new ForbiddenApiCatalog();
        catalog.init();
        scanner = new ForbiddenApiScanner(null, catalog);
    }

    private List<String> categories(String source) throws IOException {
        Path file = dir.resolve("Sample.java");
        Files.writeString(file, source);
        return scanner.scanJavaFile(file).stream().map(ForbiddenApiViolation::category).toList();
    }

    @Nested
    class ArchetypeBaseline {

        /** The AEM archetype's HelloWorldModel used to produce two CRITICAL findings. */
        @Test
        void helloWorldModelIsClean() throws IOException {
            assertThat(categories("""
                import org.apache.sling.api.resource.Resource;
                import org.apache.sling.api.resource.ResourceResolver;
                import org.apache.sling.models.annotations.Model;
                import org.apache.sling.models.annotations.injectorspecific.SlingObject;
                import com.day.cq.wcm.api.Page;
                import com.day.cq.wcm.api.PageManager;
                @Model(adaptables = Resource.class)
                public class HelloWorldModel {
                    @SlingObject private ResourceResolver resourceResolver;
                    void init() {
                        PageManager pageManager = resourceResolver.adaptTo(PageManager.class);
                    }
                }
                """)).isEmpty();
        }

        @Test
        void slingAllMethodsServletAndReplicatorAreNotDeprecated() throws IOException {
            assertThat(categories("""
                import org.apache.sling.api.servlets.SlingAllMethodsServlet;
                import com.day.cq.replication.Replicator;
                import com.day.cq.replication.ReplicationActionType;
                import com.day.cq.tagging.TagManager;
                public class PublishServlet extends SlingAllMethodsServlet {
                    void x(com.day.cq.dam.api.Asset asset) { asset.getRendition("original"); }
                }
                """)).isEmpty();
        }

        /** Findings that came from Adobe's own Core Components before these rules were fixed. */
        @Test
        void coreComponentsIdiomsAreClean() throws IOException {
            assertThat(categories("""
                import com.day.cq.dam.api.handler.AssetHandler;
                import com.day.cq.wcm.commons.WCMUtils;
                import org.apache.sling.jcr.api.SlingRepository;
                public class AdaptiveImage {
                    AssetHandler handler;
                    void x(org.apache.sling.api.resource.ResourceResolver rr) {
                        rr.findResources("SELECT * FROM [cq:Page]", "JCR-SQL2");
                    }
                }
                """)).isEmpty();
        }

        @Test
        void customAssetHandlerIsFlagged() throws IOException {
            assertThat(categories("""
                import com.day.cq.dam.commons.handler.AbstractAssetHandler;
                public class TiffHandler extends AbstractAssetHandler {}
                """)).containsExactly("DEPRECATED_DAM");
        }

        @Test
        void replicationAgentsAreStillFlagged() throws IOException {
            assertThat(categories("""
                import com.day.cq.replication.AgentManager;
                public class Agents {}
                """)).containsExactly("DEPRECATED_REPLICATION");
        }
    }

    @Nested
    class ThreadUnsafeFields {

        @Test
        void resolverFieldOnOsgiComponentIsFlagged() throws IOException {
            assertThat(categories("""
                import org.apache.sling.api.resource.ResourceResolver;
                import org.osgi.service.component.annotations.Component;
                @Component(service = Foo.class)
                public class Foo {
                    private ResourceResolver resolver;
                }
                """)).containsExactly("THREAD_UNSAFE_FIELDS");
        }

        /** ACS AEM Commons suppresses a deliberate observation session exactly this way. */
        @Test
        void suppressWarningsWithRuleIdIsHonored() throws IOException {
            assertThat(categories("""
                import javax.jcr.Session;
                import org.osgi.service.component.annotations.Component;
                @Component
                public class Listener {
                    @SuppressWarnings("AEM Rules:AEM-3") // used for observation
                    private Session session;
                }
                """)).isEmpty();
        }

        @Test
        void unrelatedSuppressWarningsDoesNotHide() throws IOException {
            assertThat(categories("""
                import javax.jcr.Session;
                import org.osgi.service.component.annotations.Component;
                @Component
                public class Listener {
                    @SuppressWarnings("unchecked")
                    private Session session;
                }
                """)).containsExactly("THREAD_UNSAFE_FIELDS");
        }

        @Test
        void sessionFieldOnServletIsFlagged() throws IOException {
            assertThat(categories("""
                import javax.jcr.Session;
                import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
                public class MyServlet extends SlingSafeMethodsServlet {
                    private Session session;
                }
                """)).containsExactly("THREAD_UNSAFE_FIELDS");
        }

        @Test
        void localVariableOnComponentIsFine() throws IOException {
            assertThat(categories("""
                import com.day.cq.wcm.api.PageManager;
                import org.osgi.service.component.annotations.Component;
                @Component
                public class Foo {
                    void run(org.apache.sling.api.resource.Resource r) {
                        PageManager pm = r.getResourceResolver().adaptTo(PageManager.class);
                    }
                }
                """)).isEmpty();
        }
    }

    @Nested
    class UnclosedResourceResolver {

        @Test
        void tryWithResourcesIsFine() throws IOException {
            assertThat(categories("""
                import org.apache.sling.api.resource.ResourceResolver;
                import org.apache.sling.api.resource.ResourceResolverFactory;
                public class Job {
                    ResourceResolverFactory factory;
                    void run() throws Exception {
                        try (ResourceResolver rr = factory.getServiceResourceResolver(null)) {
                            rr.getResource("/content");
                        }
                    }
                }
                """)).isEmpty();
        }

        @Test
        void explicitCloseIsFine() throws IOException {
            assertThat(categories("""
                public class Job {
                    org.apache.sling.api.resource.ResourceResolverFactory factory;
                    void run() throws Exception {
                        var rr = factory.getServiceResourceResolver(null);
                        try { rr.getResource("/content"); } finally { rr.close(); }
                    }
                }
                """)).isEmpty();
        }

        @Test
        void neverClosedIsFlagged() throws IOException {
            assertThat(categories("""
                public class Job {
                    org.apache.sling.api.resource.ResourceResolverFactory factory;
                    void run() throws Exception {
                        var rr = factory.getServiceResourceResolver(null);
                        rr.getResource("/content");
                    }
                }
                """)).containsExactly("UNCLOSED_RESOURCE_RESOLVER");
        }

        @Test
        void requestResolverIsNeverReported() throws IOException {
            assertThat(categories("""
                public class Model {
                    void init(org.apache.sling.api.SlingHttpServletRequest request) {
                        var rr = request.getResourceResolver();
                        rr.getResource("/content");
                    }
                }
                """)).isEmpty();
        }

        @Test
        void returnedResolverIsCallersResponsibility() throws IOException {
            assertThat(categories("""
                public class Helper {
                    org.apache.sling.api.resource.ResourceResolverFactory factory;
                    org.apache.sling.api.resource.ResourceResolver open() throws Exception {
                        return factory.getServiceResourceResolver(null);
                    }
                }
                """)).isEmpty();
        }
    }

    @Nested
    class HttpClients {

        @Test
        void createDefaultInMethodHasNoTimeoutAndIsNotReused() throws IOException {
            assertThat(categories("""
                import org.apache.http.impl.client.HttpClients;
                public class Fetcher {
                    void fetch() {
                        var client = HttpClients.createDefault();
                    }
                }
                """)).containsExactlyInAnyOrder("HTTP_CLIENT_TIMEOUT", "HTTP_CLIENT_REUSE");
        }

        @Test
        void configuredClientBuiltInActivateIsFine() throws IOException {
            assertThat(categories("""
                import org.apache.http.client.config.RequestConfig;
                import org.apache.http.impl.client.CloseableHttpClient;
                import org.apache.http.impl.client.HttpClients;
                import org.osgi.service.component.annotations.Activate;
                public class Fetcher {
                    private CloseableHttpClient client;
                    @Activate
                    void activate() {
                        client = HttpClients.custom()
                            .setDefaultRequestConfig(RequestConfig.custom().setConnectTimeout(5000).build())
                            .build();
                    }
                }
                """)).isEmpty();
        }

        @Test
        void importAloneIsNotAFinding() throws IOException {
            assertThat(categories("""
                import org.apache.http.client.HttpClient;
                import java.net.HttpURLConnection;
                public class Holder { HttpClient client; }
                """)).isEmpty();
        }

        @Test
        void urlConnectionWithoutTimeoutsIsFlagged() throws IOException {
            assertThat(categories("""
                import java.net.URL;
                public class Fetcher {
                    void fetch() throws Exception {
                        URL url = new URL("https://example.com");
                        url.openConnection().getInputStream();
                    }
                }
                """)).containsExactly("HTTP_CLIENT_TIMEOUT");
        }

        @Test
        void connectionFactoryMethodLeavesTimeoutsToCaller() throws IOException {
            assertThat(categories("""
                import java.net.HttpURLConnection;
                import java.net.URL;
                public class Fetcher {
                    protected HttpURLConnection openConnection() throws Exception {
                        return (HttpURLConnection) new URL("https://example.com").openConnection();
                    }
                }
                """)).isEmpty();
        }

        @Test
        void urlConnectionWithTimeoutsIsFine() throws IOException {
            assertThat(categories("""
                import java.net.HttpURLConnection;
                import java.net.URL;
                public class Fetcher {
                    void fetch() throws Exception {
                        URL url = new URL("https://example.com");
                        HttpURLConnection c = (HttpURLConnection) url.openConnection();
                        c.setConnectTimeout(5000);
                        c.setReadTimeout(5000);
                    }
                }
                """)).isEmpty();
        }
    }
}
