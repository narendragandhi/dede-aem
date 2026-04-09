package com.dede.security;

import com.dede.cloud.ForbiddenApiCatalog;
import com.dede.cloud.ForbiddenApiScanner;
import com.dede.cloud.SupplyChainReport;
import com.dede.domain.GraphAnalyzer;
import com.dede.domain.GraphExporter;
import com.dede.domain.GraphRepository;
import com.dede.domain.GraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for Strand 6: Enhanced Security Analysis (Beads 6.1–6.4).
 */
class SecurityAnalysisTest {

    // -----------------------------------------------------------------------
    // Bead 6.1: ServletSecurityAuditor
    // -----------------------------------------------------------------------
    @Nested
    class ServletAuditorTests {

        private ServletSecurityAuditor auditor;

        @BeforeEach
        void setUp() {
            // Empty graph — tests use fallback AST scan
            GraphRepository repo = new GraphRepository();
            GraphService graphService = new GraphService(repo, new GraphAnalyzer(repo), new GraphExporter(repo));
            auditor = new ServletSecurityAuditor(graphService);
        }

        @TempDir Path projectRoot;

        @Test
        void flagsPathBasedServlet() throws IOException {
            writeJava(projectRoot, "MyServlet.java", """
                package com.example;
                import org.apache.sling.api.SlingHttpServletRequest;
                import org.apache.sling.api.SlingHttpServletResponse;
                import org.apache.sling.api.servlets.SlingAllMethodsServlet;
                import org.apache.sling.servlets.annotations.SlingServletPaths;
                @SlingServletPaths(value = "/bin/my-servlet")
                public class MyServlet extends SlingAllMethodsServlet {
                    protected void doGet(SlingHttpServletRequest req, SlingHttpServletResponse res) {}
                }
                """);

            var result = auditor.audit(projectRoot);
            assertThat(result.findings())
                .extracting(f -> f.type())
                .contains(ServletSecurityAuditor.FindingType.PATH_BASED_REGISTRATION);
        }

        @Test
        void flagsDeleteWithoutAuthCheck() throws IOException {
            writeJava(projectRoot, "DeleteServlet.java", """
                package com.example;
                import org.apache.sling.api.SlingHttpServletRequest;
                import org.apache.sling.api.SlingHttpServletResponse;
                import org.apache.sling.api.servlets.SlingAllMethodsServlet;
                import org.apache.sling.servlets.annotations.SlingServletPaths;
                @SlingServletPaths(value = "/bin/delete")
                public class DeleteServlet extends SlingAllMethodsServlet {
                    protected void doDelete(SlingHttpServletRequest req,
                                            SlingHttpServletResponse res) {
                        // no auth check
                        res.setStatus(204);
                    }
                }
                """);

            var result = auditor.audit(projectRoot);
            assertThat(result.findings())
                .extracting(f -> f.type())
                .contains(ServletSecurityAuditor.FindingType.MISSING_AUTH_CHECK);
        }

        @Test
        void doesNotFlagDeleteWithAuthCheck() throws IOException {
            writeJava(projectRoot, "SafeDelete.java", """
                package com.example;
                import org.apache.sling.api.SlingHttpServletRequest;
                import org.apache.sling.api.SlingHttpServletResponse;
                import org.apache.sling.api.servlets.SlingAllMethodsServlet;
                import org.apache.sling.servlets.annotations.SlingServletResourceTypes;
                @SlingServletResourceTypes(resourceTypes = "example/components/item",
                                           methods = "DELETE")
                public class SafeDelete extends SlingAllMethodsServlet {
                    protected void doDelete(SlingHttpServletRequest req,
                                            SlingHttpServletResponse res) throws Exception {
                        req.getResourceResolver().adaptTo(javax.jcr.Session.class)
                           .checkPermission("/content", "remove_node");
                    }
                }
                """);

            var result = auditor.audit(projectRoot);
            long missingAuth = result.findings().stream()
                .filter(f -> f.type() == ServletSecurityAuditor.FindingType.MISSING_AUTH_CHECK)
                .count();
            assertThat(missingAuth).isZero();
        }

        @Test
        void flagsStateChangingGet() throws IOException {
            writeJava(projectRoot, "BadGet.java", """
                package com.example;
                import org.apache.sling.api.SlingHttpServletRequest;
                import org.apache.sling.api.SlingHttpServletResponse;
                import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
                import org.apache.sling.servlets.annotations.SlingServletPaths;
                @SlingServletPaths("/bin/bad")
                public class BadGet extends SlingSafeMethodsServlet {
                    protected void doGet(SlingHttpServletRequest req,
                                         SlingHttpServletResponse res) throws Exception {
                        req.getResourceResolver().getResource("/content/x")
                           .adaptTo(javax.jcr.Node.class).setProperty("key", "val");
                        req.getResourceResolver().commit();
                    }
                }
                """);

            var result = auditor.audit(projectRoot);
            assertThat(result.findings())
                .extracting(f -> f.type())
                .contains(ServletSecurityAuditor.FindingType.STATE_CHANGING_GET);
        }
    }

    // -----------------------------------------------------------------------
    // Bead 6.2: XssDetector
    // -----------------------------------------------------------------------
    @Nested
    class XssDetectorTests {

        private XssDetector detector;

        @BeforeEach
        void setUp() {
            detector = new XssDetector();
        }

        @TempDir Path projectRoot;

        @Test
        void flagsUnsafeContext() throws IOException {
            writeHtl(projectRoot, "evil.html",
                "<sly data-sly-test=\"${model.text @ context='unsafe'}\"></sly>");

            var result = detector.scan(projectRoot);
            assertThat(result.findings())
                .extracting(XssDetector.XssFinding::type)
                .contains(XssDetector.FindingType.UNSAFE_CONTEXT);
            assertThat(result.findings())
                .extracting(XssDetector.XssFinding::severity)
                .contains(XssDetector.Severity.CRITICAL);
        }

        @Test
        void flagsHtmlContextWithUserInput() throws IOException {
            writeHtl(projectRoot, "risky.html",
                "<div>${request.getParameter('q') @ context='html'}</div>");

            var result = detector.scan(projectRoot);
            // Should be CRITICAL because user input + html context
            assertThat(result.findings())
                .extracting(XssDetector.XssFinding::severity)
                .contains(XssDetector.Severity.CRITICAL);
        }

        @Test
        void flagsHtmlContextWithoutUserInput() throws IOException {
            writeHtl(projectRoot, "review.html",
                "<div>${model.description @ context='html'}</div>");

            var result = detector.scan(projectRoot);
            assertThat(result.findings())
                .extracting(XssDetector.XssFinding::type)
                .contains(XssDetector.FindingType.HTML_CONTEXT);
            // Should be HIGH (not CRITICAL — no confirmed user input)
            assertThat(result.findings().stream()
                .filter(f -> f.type() == XssDetector.FindingType.HTML_CONTEXT)
                .map(XssDetector.XssFinding::severity).findFirst())
                .contains(XssDetector.Severity.HIGH);
        }

        @Test
        void flagsUnsafeHrefAttribute() throws IOException {
            writeHtl(projectRoot, "link.html",
                "<a href=\"${model.url}\">click</a>");

            var result = detector.scan(projectRoot);
            assertThat(result.findings())
                .extracting(XssDetector.XssFinding::type)
                .contains(XssDetector.FindingType.UNSAFE_ATTRIBUTE);
        }

        @Test
        void cleanTemplateProducesNoFindings() throws IOException {
            writeHtl(projectRoot, "clean.html",
                "<div data-sly-text=\"${model.title}\"></div>");

            var result = detector.scan(projectRoot);
            assertThat(result.findings()).isEmpty();
        }
    }

    // -----------------------------------------------------------------------
    // Bead 6.3: AclAnalyzer
    // -----------------------------------------------------------------------
    @Nested
    class AclAnalyzerTests {

        private AclAnalyzer analyzer;

        @BeforeEach
        void setUp() {
            analyzer = new AclAnalyzer();
        }

        @TempDir Path projectRoot;

        @Test
        void flagsEveryoneWithWriteGrant() throws IOException {
            writeContentXml(projectRoot, "jcr_root/content/mysite/.content.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <jcr:root xmlns:jcr="http://www.jcp.org/jcr/1.0"
                          xmlns:rep="internal"
                          jcr:primaryType="cq:Page">
                  <rep:policy jcr:primaryType="rep:ACL">
                    <allow jcr:primaryType="rep:GrantACE"
                           rep:principalName="everyone"
                           rep:privileges="[jcr:write]"/>
                  </rep:policy>
                </jcr:root>
                """);

            var result = analyzer.analyze(projectRoot);
            assertThat(result.findings())
                .extracting(AclAnalyzer.AclFinding::type)
                .contains(AclAnalyzer.FindingType.PUBLIC_PRINCIPAL_GRANT);
        }

        @Test
        void flagsJcrAllPrivilege() throws IOException {
            writeContentXml(projectRoot, "jcr_root/apps/myapp/.content.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <jcr:root xmlns:jcr="http://www.jcp.org/jcr/1.0"
                          xmlns:rep="internal"
                          jcr:primaryType="cq:Page">
                  <rep:policy jcr:primaryType="rep:ACL">
                    <allow jcr:primaryType="rep:GrantACE"
                           rep:principalName="my-service-user"
                           rep:privileges="[jcr:all]"/>
                  </rep:policy>
                </jcr:root>
                """);

            var result = analyzer.analyze(projectRoot);
            assertThat(result.findings())
                .extracting(AclAnalyzer.AclFinding::type)
                .contains(AclAnalyzer.FindingType.JCR_ALL_PRIVILEGE);
        }

        @Test
        void cleanAclProducesNoFindings() throws IOException {
            writeContentXml(projectRoot, "jcr_root/content/safe/.content.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <jcr:root xmlns:jcr="http://www.jcp.org/jcr/1.0"
                          xmlns:rep="internal"
                          jcr:primaryType="cq:Page">
                  <rep:policy jcr:primaryType="rep:ACL">
                    <allow jcr:primaryType="rep:GrantACE"
                           rep:principalName="content-authors"
                           rep:privileges="[jcr:read]"/>
                  </rep:policy>
                </jcr:root>
                """);

            var result = analyzer.analyze(projectRoot);
            assertThat(result.findings()).isEmpty();
        }
    }

    // -----------------------------------------------------------------------
    // Bead 6.4: SecurityReport aggregator
    // -----------------------------------------------------------------------
    @Nested
    class SecurityReportTests {

        @TempDir Path projectRoot;

        private SecurityReport emptyReport() {
            var sc = new SupplyChainReport(List.of(), projectRoot);
            var servlet = new ServletSecurityAuditor.AuditResult(List.of());
            var xss = new XssDetector.ScanResult(List.of());
            var acl = new AclAnalyzer.AnalysisResult(List.of());
            return new SecurityReport(sc, servlet, xss, acl, projectRoot);
        }

        @Test
        void cleanProjectScores100() {
            assertThat(emptyReport().getScore()).isEqualTo(100);
        }

        @Test
        void criticalFindingLowersScore() {
            var xssFindings = List.of(new XssDetector.XssFinding(
                "foo.html", 1, XssDetector.Severity.CRITICAL,
                XssDetector.FindingType.UNSAFE_CONTEXT,
                "unsafe context", "fix it"
            ));
            var sc = new SupplyChainReport(List.of(), projectRoot);
            var report = new SecurityReport(sc,
                new ServletSecurityAuditor.AuditResult(List.of()),
                new XssDetector.ScanResult(xssFindings),
                new AclAnalyzer.AnalysisResult(List.of()),
                projectRoot);
            assertThat(report.getScore()).isLessThan(100);
            assertThat(report.hasCritical()).isTrue();
        }

        @Test
        void markdownContainsSummaryTable() {
            assertThat(emptyReport().toMarkdown())
                .contains("Security Score")
                .contains("Summary")
                .contains("Supply Chain");
        }

        @Test
        void jsonContainsAllCategories() {
            Map<String, Object> json = emptyReport().toJson();
            assertThat(json).containsKeys("score", "critical", "high", "medium",
                "supplyChain", "servletAudit", "xss", "acl");
        }

        @Test
        void sarifDocumentIsValid() {
            Map<String, Object> sarif = emptyReport().toSarif();
            assertThat(sarif).containsKey("$schema");
            assertThat(sarif.get("version")).isEqualTo("2.1.0");
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------
    private static void writeJava(Path root, String name, String content) throws IOException {
        Path f = root.resolve(name);
        Files.createDirectories(f.getParent());
        Files.writeString(f, content);
    }

    private static void writeHtl(Path root, String name, String content) throws IOException {
        Path f = root.resolve(name);
        Files.createDirectories(f.getParent());
        Files.writeString(f, content);
    }

    private static void writeContentXml(Path root, String relPath, String content) throws IOException {
        Path f = root.resolve(relPath);
        Files.createDirectories(f.getParent());
        Files.writeString(f, content);
    }
}
