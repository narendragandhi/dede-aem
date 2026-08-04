package com.dede.maven;

import com.dede.intelligence.CloudReadinessAnalyzer.CloudIssue;
import com.dede.intelligence.CloudReadinessAnalyzer.CloudReadinessReport;
import com.dede.intelligence.CloudReadinessAnalyzer.Category;
import com.dede.intelligence.CloudReadinessAnalyzer.Severity;
import org.apache.maven.plugin.MojoFailureException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Directly tests DedeCheckMojo.checkCritical() -- the actual fail-the-build decision
 * this plugin exists to make -- against a hand-built CloudReadinessReport, independent
 * of the full scan pipeline and Spring bootstrap. Getting a real forbidden-API rule to
 * trigger through a synthetic project turned out to depend on graph-building details
 * (exact type resolution of injected OSGi references) that are a separate concern from
 * whether this Mojo's own gating logic is correct.
 */
class DedeCheckMojoTest {

    private CloudReadinessReport reportWith(CloudIssue... issues) {
        CloudReadinessReport report = new CloudReadinessReport();
        report.setIssues(List.of(issues));
        return report;
    }

    @Test
    void throwsOnCriticalIssueWhenFailOnCriticalEnabled() {
        DedeCheckMojo mojo = new DedeCheckMojo();
        mojo.setFailOnCritical(true);
        CloudReadinessReport report = reportWith(
            new CloudIssue(Severity.CRITICAL, Category.DEPRECATED_API,
                "Service uses admin session/resolver. Must use service users in Cloud.",
                "AdminServlet.java", 12)
        );

        assertThatThrownBy(() -> mojo.checkCritical(report))
            .isInstanceOf(MojoFailureException.class)
            .hasMessageContaining("1 CRITICAL")
            .hasMessageContaining("admin session/resolver");
    }

    @Test
    void doesNotThrowWhenNoCriticalIssues() {
        DedeCheckMojo mojo = new DedeCheckMojo();
        mojo.setFailOnCritical(true);
        CloudReadinessReport report = reportWith(
            new CloudIssue(Severity.HIGH, Category.DEPRECATED_API, "High severity only", "Foo.java", 1),
            new CloudIssue(Severity.MEDIUM, Category.DEPRECATED_API, "Medium severity only", "Bar.java", 2)
        );

        assertThatCode(() -> mojo.checkCritical(report)).doesNotThrowAnyException();
    }

    @Test
    void doesNotThrowOnEmptyReport() {
        DedeCheckMojo mojo = new DedeCheckMojo();
        mojo.setFailOnCritical(true);

        assertThatCode(() -> mojo.checkCritical(reportWith())).doesNotThrowAnyException();
    }

    @Test
    void doesNotThrowWhenFailOnCriticalDisabledEvenWithCriticalIssues() throws MojoFailureException {
        DedeCheckMojo mojo = new DedeCheckMojo();
        mojo.setFailOnCritical(false);
        CloudReadinessReport report = reportWith(
            new CloudIssue(Severity.CRITICAL, Category.DEPRECATED_API, "Ignored by design", "Foo.java", 1)
        );

        // Should not throw -- explicit call (not assertThatThrownBy) so the test
        // itself fails loudly if this regresses to throwing.
        mojo.checkCritical(report);
    }

    @Test
    void messageListsEachCriticalIssueSeparately() {
        DedeCheckMojo mojo = new DedeCheckMojo();
        mojo.setFailOnCritical(true);
        CloudReadinessReport report = reportWith(
            new CloudIssue(Severity.CRITICAL, Category.DEPRECATED_API, "First critical issue", "A.java", 1),
            new CloudIssue(Severity.CRITICAL, Category.DEPRECATED_API, "Second critical issue", "B.java", 2),
            new CloudIssue(Severity.HIGH, Category.DEPRECATED_API, "Not critical, excluded", "C.java", 3)
        );

        assertThatThrownBy(() -> mojo.checkCritical(report))
            .hasMessageContaining("2 CRITICAL")
            .hasMessageContaining("First critical issue")
            .hasMessageContaining("Second critical issue")
            .hasMessageNotContaining("Not critical, excluded");
    }
}
