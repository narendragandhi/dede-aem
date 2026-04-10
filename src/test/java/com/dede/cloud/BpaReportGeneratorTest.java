package com.dede.cloud;

import com.dede.intelligence.CloudReadinessAnalyzer.Category;
import com.dede.intelligence.CloudReadinessAnalyzer.CloudIssue;
import com.dede.intelligence.CloudReadinessAnalyzer.CloudReadinessReport;
import com.dede.intelligence.CloudReadinessAnalyzer.Severity;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Tests for BpaReportGenerator — BPA-compatible report generation.
 */
@DisplayName("BPA Report Generator Tests")
class BpaReportGeneratorTest {

    @TempDir
    Path tempDir;

    private BpaReportGenerator generator;

    @BeforeEach
    void setUp() {
        // BpaReportGenerator takes CloudReadinessAnalyzer and ForbiddenApiCatalog
        var analyzer = mock(com.dede.intelligence.CloudReadinessAnalyzer.class);
        var catalog = mock(ForbiddenApiCatalog.class);
        generator = new BpaReportGenerator(analyzer, catalog);
    }

    // -----------------------------------------------------------------------
    // Report generation
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Report Structure")
    class ReportStructure {

        @Test
        @DisplayName("Report contains project name and metadata")
        void reportContainsMetadata() {
            CloudReadinessReport cloudReport = emptyReport();

            BpaReportGenerator.BpaReport report = generator.generateReport(cloudReport, "MyAEMProject");

            assertThat(report.getProjectName()).isEqualTo("MyAEMProject");
            assertThat(report.getGeneratedAt()).isNotNull();
            assertThat(report.getGeneratedBy()).contains("Dede");
            assertThat(report.getVersion()).isNotNull();
        }

        @Test
        @DisplayName("Empty report has zero findings and READY verdict")
        void emptyReportIsReady() {
            CloudReadinessReport cloudReport = emptyReport();

            BpaReportGenerator.BpaReport report = generator.generateReport(cloudReport, "TestProject");

            assertThat(report.getFindings()).isEmpty();
            assertThat(report.getSummary().getVerdict()).isEqualTo("READY");
        }

        @Test
        @DisplayName("Report includes rule reference list")
        void reportIncludesRules() {
            CloudReadinessReport cloudReport = emptyReport();

            BpaReportGenerator.BpaReport report = generator.generateReport(cloudReport, "TestProject");

            assertThat(report.getRules()).isNotEmpty();
            assertThat(report.getRules()).extracting(BpaReportGenerator.CstRule::getId)
                .contains("CST-1", "CST-6", "AEM-11");
        }
    }

    // -----------------------------------------------------------------------
    // Severity mapping
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Severity Mapping")
    class SeverityMapping {

        @Test
        @DisplayName("CRITICAL issues map to BLOCKER verdict")
        void criticalIssueTriggersNotReady() {
            CloudReadinessReport cloudReport = reportWithIssue(
                Severity.CRITICAL, Category.DEPRECATED_API,
                "adminSession usage detected", "MyServlet.java", 42);

            BpaReportGenerator.BpaReport report = generator.generateReport(cloudReport, "P");

            assertThat(report.getSummary().getVerdict()).isEqualTo("NOT_READY");
            assertThat(report.getSummary().getBlockers()).isGreaterThan(0);
        }

        @Test
        @DisplayName("HIGH issues map to CRITICAL BPA severity")
        void highMapsToCreitical() {
            CloudReadinessReport cloudReport = reportWithIssue(
                Severity.HIGH, Category.CLOUD_INCOMPATIBLE,
                "Replication API usage", "MyService.java", 10);

            BpaReportGenerator.BpaReport report = generator.generateReport(cloudReport, "P");

            assertThat(report.getFindings().get(0).getSeverity()).isEqualTo("CRITICAL");
        }

        @Test
        @DisplayName("MEDIUM issues map to MAJOR BPA severity")
        void mediumMapsToMajor() {
            CloudReadinessReport cloudReport = reportWithIssue(
                Severity.MEDIUM, Category.HARDCODED_PATH,
                "Hardcoded path /tmp/", "Util.java", 5);

            BpaReportGenerator.BpaReport report = generator.generateReport(cloudReport, "P");

            assertThat(report.getFindings().get(0).getSeverity()).isEqualTo("MAJOR");
        }

        @Test
        @DisplayName("LOW issues map to MINOR BPA severity")
        void lowMapsToMinor() {
            CloudReadinessReport cloudReport = reportWithIssue(
                Severity.LOW, Category.OSGI_CONFIG,
                "Config hint", "Config.java", 1);

            BpaReportGenerator.BpaReport report = generator.generateReport(cloudReport, "P");

            assertThat(report.getFindings().get(0).getSeverity()).isEqualTo("MINOR");
        }
    }

    // -----------------------------------------------------------------------
    // CST rule mapping
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("CST Rule Mapping")
    class CstRuleMapping {

        @Test
        @DisplayName("Admin session maps to AEM-11")
        void adminSessionMapsToAem11() {
            CloudReadinessReport cloudReport = reportWithIssue(
                Severity.CRITICAL, Category.SECURITY,
                "admin session usage: loginAdministrative should be replaced with service resolver", "MyComp.java", 1);

            BpaReportGenerator.BpaReport report = generator.generateReport(cloudReport, "P");

            assertThat(report.getFindings().get(0).getRuleId()).isEqualTo("AEM-11");
        }

        @Test
        @DisplayName("Replication API maps to CST-4")
        void replicationMapsToCST4() {
            CloudReadinessReport cloudReport = reportWithIssue(
                Severity.HIGH, Category.CLOUD_INCOMPATIBLE,
                "ReplicationAction deprecated — use Sling Content Distribution", "MyRep.java", 1);

            BpaReportGenerator.BpaReport report = generator.generateReport(cloudReport, "P");

            assertThat(report.getFindings().get(0).getRuleId()).isEqualTo("CST-4");
        }

        @Test
        @DisplayName("Workflow process maps to CST-6")
        void workflowMapsToCST6() {
            CloudReadinessReport cloudReport = reportWithIssue(
                Severity.MEDIUM, Category.CLOUD_INCOMPATIBLE,
                "Legacy workflow process step usage", "WfProcess.java", 1);

            BpaReportGenerator.BpaReport report = generator.generateReport(cloudReport, "P");

            assertThat(report.getFindings().get(0).getRuleId()).isEqualTo("CST-6");
        }

        @Test
        @DisplayName("Path-based servlet maps to CQBP-75")
        void pathServletMapsToCQBP75() {
            CloudReadinessReport cloudReport = reportWithIssue(
                Severity.MEDIUM, Category.CLOUD_INCOMPATIBLE,
                "Servlet registered by path instead of resource type", "MyServlet.java", 1);

            BpaReportGenerator.BpaReport report = generator.generateReport(cloudReport, "P");

            assertThat(report.getFindings().get(0).getRuleId()).isEqualTo("CQBP-75");
        }
    }

    // -----------------------------------------------------------------------
    // Summary calculations
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Summary Calculations")
    class SummaryCalculations {

        @Test
        @DisplayName("Summary counts match finding counts")
        void summaryCountsMatchFindings() {
            CloudReadinessReport cloudReport = new CloudReadinessReport();
            cloudReport.getIssues().add(new CloudIssue(Severity.CRITICAL, Category.SECURITY, "a", "f", 1));
            cloudReport.getIssues().add(new CloudIssue(Severity.HIGH, Category.DEPRECATED_API, "b", "f", 2));
            cloudReport.getIssues().add(new CloudIssue(Severity.MEDIUM, Category.HARDCODED_PATH, "c", "f", 3));
            cloudReport.getIssues().add(new CloudIssue(Severity.LOW, Category.OSGI_CONFIG, "d", "f", 4));
            cloudReport.calculateScore();

            BpaReportGenerator.BpaReport report = generator.generateReport(cloudReport, "P");
            BpaReportGenerator.BpaSummary summary = report.getSummary();

            assertThat(summary.getTotalFindings()).isEqualTo(4);
            assertThat(summary.getBlockers()).isEqualTo(1);   // CRITICAL -> BLOCKER
            assertThat(summary.getCritical()).isEqualTo(1);   // HIGH -> CRITICAL
            assertThat(summary.getMajor()).isEqualTo(1);      // MEDIUM -> MAJOR
            assertThat(summary.getMinor()).isEqualTo(1);      // LOW -> MINOR
        }

        @Test
        @DisplayName("Many major issues triggers NEEDS_REVIEW verdict")
        void manyMajorIssuesTriggerNeedsReview() {
            CloudReadinessReport cloudReport = new CloudReadinessReport();
            for (int i = 0; i < 6; i++) {
                cloudReport.getIssues().add(new CloudIssue(Severity.MEDIUM, Category.HARDCODED_PATH,
                    "issue " + i, "file.java", i));
            }
            cloudReport.calculateScore();

            BpaReportGenerator.BpaReport report = generator.generateReport(cloudReport, "P");

            assertThat(report.getSummary().getVerdict()).isEqualTo("NEEDS_REVIEW");
        }

        @Test
        @DisplayName("findingsByRule groups correctly")
        void findingsByRuleGroupsCorrectly() {
            CloudReadinessReport cloudReport = new CloudReadinessReport();
            cloudReport.getIssues().add(new CloudIssue(Severity.CRITICAL, Category.SECURITY,
                "admin session usage detected", "A.java", 1));
            cloudReport.getIssues().add(new CloudIssue(Severity.CRITICAL, Category.SECURITY,
                "getAdministrativeResourceResolver admin resolver pattern detected", "B.java", 1));
            cloudReport.calculateScore();

            BpaReportGenerator.BpaReport report = generator.generateReport(cloudReport, "P");

            assertThat(report.getSummary().getFindingsByRule()).containsKey("AEM-11");
            assertThat(report.getSummary().getFindingsByRule().get("AEM-11")).isEqualTo(2);
        }
    }

    // -----------------------------------------------------------------------
    // Export methods
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Export Methods")
    class ExportMethods {

        @Test
        @DisplayName("Exports valid JSON file")
        void exportsJsonFile() throws IOException {
            CloudReadinessReport cloudReport = emptyReport();
            BpaReportGenerator.BpaReport report = generator.generateReport(cloudReport, "TestProject");

            Path jsonFile = tempDir.resolve("report.json");
            generator.exportToJson(report, jsonFile);

            assertThat(Files.exists(jsonFile)).isTrue();
            String content = Files.readString(jsonFile);
            assertThat(content).contains("TestProject");
            assertThat(content).contains("projectName");
        }

        @Test
        @DisplayName("Exports valid HTML file")
        void exportsHtmlFile() throws IOException {
            CloudReadinessReport cloudReport = reportWithIssue(
                Severity.HIGH, Category.DEPRECATED_API,
                "Deprecated API usage", "MyClass.java", 10);
            BpaReportGenerator.BpaReport report = generator.generateReport(cloudReport, "HtmlProject");

            Path htmlFile = tempDir.resolve("report.html");
            generator.exportToHtml(report, htmlFile);

            assertThat(Files.exists(htmlFile)).isTrue();
            String content = Files.readString(htmlFile);
            assertThat(content).contains("<!DOCTYPE html>");
            assertThat(content).contains("HtmlProject");
            assertThat(content).contains("CRITICAL"); // HIGH maps to CRITICAL in BPA
        }

        @Test
        @DisplayName("HTML escapes XSS in message content")
        void htmlEscapesXssContent() throws IOException {
            CloudReadinessReport cloudReport = reportWithIssue(
                Severity.LOW, Category.SECURITY,
                "<script>alert('xss')</script>", "file.java", 1);
            BpaReportGenerator.BpaReport report = generator.generateReport(cloudReport, "P");

            Path htmlFile = tempDir.resolve("xss-test.html");
            generator.exportToHtml(report, htmlFile);

            String content = Files.readString(htmlFile);
            assertThat(content).doesNotContain("<script>alert('xss')</script>");
            assertThat(content).contains("&lt;script&gt;");
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static CloudReadinessReport emptyReport() {
        CloudReadinessReport r = new CloudReadinessReport();
        r.calculateScore();
        return r;
    }

    private static CloudReadinessReport reportWithIssue(
            Severity severity, Category category, String message, String file, int line) {
        CloudReadinessReport r = new CloudReadinessReport();
        r.getIssues().add(new CloudIssue(severity, category, message, file, line));
        r.calculateScore();
        return r;
    }
}
