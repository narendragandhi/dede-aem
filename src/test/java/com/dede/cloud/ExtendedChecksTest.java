package com.dede.cloud;

import com.dede.cloud.CloudFindings.Finding;
import com.dede.domain.model.CodeNode;
import com.dede.domain.model.NodeType;
import com.dede.security.ServiceUserAnalyzer.ServiceUserIssue;
import com.dede.security.ServiceUserAnalyzer.ServiceUserSummary;
import net.adamcin.oakpal.api.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExtendedChecksTest {

    @TempDir
    Path project;

    private LegacyPathDetector legacyPathDetector;

    @BeforeEach
    void setUp() {
        ForbiddenApiCatalog catalog = new ForbiddenApiCatalog();
        catalog.init();
        legacyPathDetector = new LegacyPathDetector(null, catalog);
    }

    private List<Finding> legacyFindings() {
        return ExtendedChecks.legacyPaths(legacyPathDetector.scanProject(project));
    }

    private void write(String relative, String content) throws IOException {
        Path file = project.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void deployingToALegacyLocationUsesCatalogSeverity() throws IOException {
        write("ui.content/src/main/content/META-INF/vault/filter.xml", """
            <workspaceFilter version="1.0">
              <filter root="/etc/designs/mysite"/>
              <filter root="/libs/cq/core/content/nav"/>
              <filter root="/content/mysite"/>
            </workspaceFilter>
            """);

        assertThat(legacyFindings())
            .extracting(Finding::severity, Finding::target)
            .containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("HIGH", "/etc/designs/mysite"),       // MAJOR
                org.assertj.core.groups.Tuple.tuple("CRITICAL", "/libs/cq/core/content/nav")); // SEVERE
    }

    @Test
    void dialogReferencesToLibsAreNotFindings() throws IOException {
        write("ui.apps/src/main/content/jcr_root/apps/mysite/components/title/_cq_dialog/.content.xml", """
            <jcr:root sling:resourceType="cq/gui/components/authoring/dialog">
              <title sling:resourceType="granite/ui/components/coral/foundation/form/textfield"
                     path="/libs/granite/ui/components/coral/foundation/form/textfield"/>
            </jcr:root>
            """);

        assertThat(legacyFindings()).isEmpty();
    }

    @Test
    void codeReferencesAreMediumHintsNotGateFailures() throws IOException {
        write("core/src/main/java/demo/Design.java", """
            class Design { String p = "/etc/designs/mysite/static.css"; }
            """);

        assertThat(legacyFindings())
            .singleElement()
            .satisfies(f -> {
                assertThat(f.severity()).isEqualTo("MEDIUM");
                assertThat(f.message()).startsWith("References legacy path /etc/designs/mysite/static.css");
            });
    }

    @Test
    void legacyPathsMatchOnSegmentBoundaries() throws IOException {
        write("ui.content/src/main/content/META-INF/vault/filter.xml", """
            <workspaceFilter version="1.0"><filter root="/etc/damage-reports"/></workspaceFilter>
            """);

        assertThat(legacyFindings()).isEmpty();
    }

    @Test
    void serviceUserIssuesMapAndDropUnactionableInfo() {
        CodeNode config = new CodeNode("cfg:mapper", "mapper", NodeType.OSGI_CONFIG, "mapper", "config/mapper.cfg.json");
        ServiceUserSummary summary = new ServiceUserSummary(List.of(), List.of(
            new ServiceUserIssue(config, ServiceUserIssue.Severity.CRITICAL, "Service user mapped to 'admin'", "Use a dedicated user"),
            new ServiceUserIssue(config, ServiceUserIssue.Severity.INFO, "Bundle not found in project", "External bundle")),
            1);

        assertThat(ExtendedChecks.serviceUsers(summary))
            .singleElement()
            .satisfies(f -> {
                assertThat(f.severity()).isEqualTo("CRITICAL");
                assertThat(f.file()).isEqualTo("config/mapper.cfg.json");
            });
    }

    @Test
    void contentPackageSeveritiesMapAndToolFailuresDoNotGate() {
        OakPalPackageValidator.PackageValidationReport report = new OakPalPackageValidator.PackageValidationReport(
            "/p", List.of(new OakPalPackageValidator.PackageValidationResult("/p/ui.apps/target/a.zip", "a.zip", List.of(
                new OakPalPackageValidator.PackageViolation(Severity.SEVERE, "ACL", "Bad ACL", null, "/apps/x"),
                new OakPalPackageValidator.PackageViolation(Severity.MINOR, "PATHS", "Minor", null, null),
                new OakPalPackageValidator.PackageViolation(Severity.SEVERE, "VALIDATION_ERROR", "Could not open", null, null)),
                false)),
            40);

        assertThat(ExtendedChecks.contentPackages(report))
            .extracting(Finding::category, Finding::severity)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("PACKAGE_ACL", "CRITICAL"),
                org.assertj.core.groups.Tuple.tuple("PACKAGE_PATHS", "MEDIUM"),
                org.assertj.core.groups.Tuple.tuple("PACKAGE_VALIDATION_ERROR", "HIGH"));
    }

    /** WKND's stock packages produced 171 of these: AEM node types aren't available offline. */
    @Test
    void offlineImportErrorsAreNotFindings() {
        OakPalPackageValidator.PackageValidationReport report = new OakPalPackageValidator.PackageValidationReport(
            "/p", List.of(new OakPalPackageValidator.PackageValidationResult("/p/ui.apps/target/a.zip", "a.zip", List.of(
                new OakPalPackageValidator.PackageViolation(Severity.MAJOR, "DefaultErrorListener",
                    "/apps/x - Importer error: javax.jcr.nodetype.NoSuchNodeTypeException \"Node type cq:Componentdoes not exist\"", null, null),
                new OakPalPackageValidator.PackageViolation(Severity.MAJOR, "DefaultErrorListener",
                    "/conf/x/settings - Importer error: java.lang.IllegalStateException \"Parent node not found.\"", null, null),
                new OakPalPackageValidator.PackageViolation(Severity.MAJOR, "DefaultErrorListener",
                    "/apps/x - Importer error: javax.jcr.AccessDeniedException \"denied\"", null, null)),
                false)),
            40);

        assertThat(ExtendedChecks.contentPackages(report))
            .singleElement()
            .satisfies(f -> assertThat(f.message()).contains("AccessDeniedException"));
    }

    @Test
    void extendedCriticalsCountTowardTheGate() {
        CloudFindings findings = new CloudFindings(
            new ForbiddenApiScanner.ScanResult(List.of()),
            new com.dede.intelligence.CloudReadinessAnalyzer.CloudReadinessReport(),
            List.of(new Finding(ExtendedChecks.SOURCE_SERVICE_USER, "SERVICE_USER_MAPPING", "CRITICAL",
                "config/mapper.cfg.json", 0, "Service user mapped to 'admin'", null, null, null, null)));

        assertThat(findings.criticalCount()).isEqualTo(1);
        assertThat(findings.criticalFailureMessage()).contains("[SERVICE_USER_MAPPING]");
    }
}
