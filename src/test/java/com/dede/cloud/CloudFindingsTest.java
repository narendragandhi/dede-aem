package com.dede.cloud;

import com.dede.intelligence.CloudReadinessAnalyzer.Category;
import com.dede.intelligence.CloudReadinessAnalyzer.CloudIssue;
import com.dede.intelligence.CloudReadinessAnalyzer.CloudReadinessReport;
import com.dede.intelligence.CloudReadinessAnalyzer.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CloudFindingsTest {

    private static ForbiddenApiScanner.ForbiddenApiViolation violation(ForbiddenApiCatalog.Severity severity) {
        return new ForbiddenApiScanner.ForbiddenApiViolation("core/Foo.java", 3,
            ForbiddenApiScanner.ViolationType.FORBIDDEN_IMPORT, "com.day.cq.wcm.core.impl.PageImpl",
            "INTERNAL_API", severity, "Internal API", "Use public API");
    }

    private static CloudReadinessReport readiness(CloudIssue... issues) {
        CloudReadinessReport report = new CloudReadinessReport();
        report.setIssues(List.of(issues));
        report.calculateScore();
        return report;
    }

    @Test
    void combinesBothSourcesIntoOneGate() {
        CloudFindings findings = new CloudFindings(
            new ForbiddenApiScanner.ScanResult(List.of(violation(ForbiddenApiCatalog.Severity.CRITICAL),
                violation(ForbiddenApiCatalog.Severity.MEDIUM))),
            readiness(new CloudIssue(Severity.CRITICAL, Category.DEPRECATED_API, "admin resolver", "Svc.java", 0),
                new CloudIssue(Severity.HIGH, Category.SECURITY, "plain-text secret", "cfg.json", 0)));

        assertThat(findings.all()).hasSize(4)
            .extracting(CloudFindings.Finding::source)
            .containsOnly(CloudFindings.SOURCE_FORBIDDEN_API, CloudFindings.SOURCE_CLOUD_READINESS);
        assertThat(findings.criticalCount()).isEqualTo(2);
        assertThat(findings.countBySeverity()).containsEntry("CRITICAL", 2L).containsEntry("HIGH", 1L)
            .containsEntry("MEDIUM", 1L);
        assertThat(findings.criticalFailureMessage())
            .contains("2 CRITICAL")
            .contains("[INTERNAL_API] Internal API (core/Foo.java:3)")
            .contains("[DEPRECATED_API] admin resolver (Svc.java)");
    }

    @Test
    void noCriticalMeansNoFailure() {
        CloudFindings findings = new CloudFindings(
            new ForbiddenApiScanner.ScanResult(List.of(violation(ForbiddenApiCatalog.Severity.HIGH))),
            readiness());

        assertThat(findings.criticalCount()).isZero();
        assertThat(findings.criticalFailureMessage()).isNull();
    }
}
