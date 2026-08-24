package com.dede.maven;

import com.dede.cloud.ForbiddenApiScanner;
import com.dede.intelligence.CloudReadinessAnalyzer.CloudIssue;
import com.dede.intelligence.CloudReadinessAnalyzer.CloudReadinessReport;
import com.dede.intelligence.CloudReadinessAnalyzer.Category;
import com.dede.intelligence.CloudReadinessAnalyzer.Severity;
import org.apache.maven.plugin.MojoFailureException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Directly tests DedeCheckMojo's gate decisions -- the actual fail-the-build logic
 * this plugin exists to make -- against hand-built reports and scan results,
 * independent of the full scan pipeline and Spring bootstrap. Getting a real
 * forbidden-API rule to trigger through a synthetic project turned out to depend on
 * graph-building details (exact type resolution of injected OSGi references) that are
 * a separate concern from whether this Mojo's own gating logic is correct.
 *
 * Gates return failure messages (null = pass); runAnalysis() aggregates them into a
 * single MojoFailureException so neither gate's findings hide the other's.
 */
class DedeCheckMojoTest {

    private CloudReadinessReport reportWith(CloudIssue... issues) {
        CloudReadinessReport report = new CloudReadinessReport();
        report.setIssues(List.of(issues));
        return report;
    }

    @Test
    void reportsCriticalIssueWhenFailOnCriticalEnabled() {
        DedeCheckMojo mojo = new DedeCheckMojo();
        mojo.setFailOnCritical(true);
        CloudReadinessReport report = reportWith(
            new CloudIssue(Severity.CRITICAL, Category.DEPRECATED_API,
                "Service uses admin session/resolver. Must use service users in Cloud.",
                "AdminServlet.java", 12)
        );

        assertThat(mojo.checkCritical(report))
            .contains("1 CRITICAL")
            .contains("admin session/resolver");
    }

    @Test
    void passesWhenNoCriticalIssues() {
        DedeCheckMojo mojo = new DedeCheckMojo();
        mojo.setFailOnCritical(true);
        CloudReadinessReport report = reportWith(
            new CloudIssue(Severity.HIGH, Category.DEPRECATED_API, "High severity only", "Foo.java", 1),
            new CloudIssue(Severity.MEDIUM, Category.DEPRECATED_API, "Medium severity only", "Bar.java", 2)
        );

        assertThat(mojo.checkCritical(report)).isNull();
    }

    @Test
    void passesOnEmptyReport() {
        DedeCheckMojo mojo = new DedeCheckMojo();
        mojo.setFailOnCritical(true);

        assertThat(mojo.checkCritical(reportWith())).isNull();
    }

    @Test
    void passesWhenFailOnCriticalDisabledEvenWithCriticalIssues() {
        DedeCheckMojo mojo = new DedeCheckMojo();
        mojo.setFailOnCritical(false);
        CloudReadinessReport report = reportWith(
            new CloudIssue(Severity.CRITICAL, Category.DEPRECATED_API, "Ignored by design", "Foo.java", 1)
        );

        assertThat(mojo.checkCritical(report)).isNull();
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

        String message = mojo.checkCritical(report);
        assertThat(message)
            .contains("2 CRITICAL")
            .contains("First critical issue")
            .contains("Second critical issue")
            .doesNotContain("Not critical, excluded");
    }

    // --- Sentinel-Twin ratchet gate ---

    private static final String LEGACY_KEY = DedeCheckMojo.baselineKey(
        violation("com.day.cq.replication.Replicator", "core/src/main/java/foo/Activator.java", 10),
        Path.of("."));

    private static ForbiddenApiScanner.ForbiddenApiViolation violation(String target, String filePath, int line) {
        return new ForbiddenApiScanner.ForbiddenApiViolation(
            filePath, line, ForbiddenApiScanner.ViolationType.FORBIDDEN_IMPORT, target,
            "legacy-replication", com.dede.cloud.ForbiddenApiCatalog.Severity.HIGH,
            "Uses legacy replication API", "Use com.adobe.granite.replication.Replicator");
    }

    private static ForbiddenApiScanner.ScanResult resultWith(ForbiddenApiScanner.ForbiddenApiViolation... violations) {
        return new ForbiddenApiScanner.ScanResult(List.of(violations));
    }

    private File writeBaseline(File dir, String... keys) throws IOException {
        File baseline = new File(dir, "dede-baseline.json");
        StringBuilder json = new StringBuilder("{\"generatedAt\":\"2026-01-01T00:00:00Z\",\"violations\":[");
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) json.append(',');
            json.append('"').append(keys[i]).append('"');
        }
        json.append("]}");
        Files.writeString(baseline.toPath(), json.toString());
        return baseline;
    }

    @Test
    void ratchetPassesWhenNoBaselineConfigured(@TempDir File dir) throws MojoFailureException {
        DedeCheckMojo mojo = new DedeCheckMojo();
        mojo.setProjectPath(dir);
        // baselineFile left null: gate disabled entirely

        assertThat(mojo.enforceRatchet(resultWith(
            violation("com.day.cq.replication.Replicator", "a/A.java", 1)))).isNull();
    }

    @Test
    void ratchetFailsWhenBaselineMissing() {
        DedeCheckMojo mojo = new DedeCheckMojo();
        mojo.setProjectPath(new File("."));
        mojo.setBaselineFile(new File("/nonexistent/dede-baseline.json"));

        // Infrastructure failure, not a finding: still throws immediately.
        assertThatThrownBy(() -> mojo.enforceRatchet(resultWith()))
            .isInstanceOf(MojoFailureException.class)
            .hasMessageContaining("baseline file not found")
            .hasMessageContaining("-Ddede.updateBaseline=true");
    }

    @Test
    void ratchetFailsOnNewViolationBeyondBaseline(@TempDir File dir) throws IOException, MojoFailureException {
        File baseline = writeBaseline(dir, LEGACY_KEY);
        DedeCheckMojo mojo = new DedeCheckMojo();
        mojo.setProjectPath(dir);
        mojo.setBaselineFile(baseline);

        ForbiddenApiScanner.ForbiddenApiViolation fresh =
            violation("com.day.cq.replication.ReplicationOptions", "core/src/Other.java", 5);
        assertThat(mojo.enforceRatchet(resultWith(fresh)))
            .contains("1 NEW forbidden-API violation")
            .contains(DedeCheckMojo.baselineKey(fresh, Path.of(".")))
            .contains("-Ddede.updateBaseline=true");
    }

    @Test
    void ratchetPassesWhenAllViolationsKnown(@TempDir File dir) throws IOException, MojoFailureException {
        File baseline = writeBaseline(dir,
            LEGACY_KEY,
            DedeCheckMojo.baselineKey(violation(
                "com.day.cq.replication.ReplicationOptions", "core/src/Other.java", 5), Path.of(".")));
        DedeCheckMojo mojo = new DedeCheckMojo();
        mojo.setProjectPath(dir);
        mojo.setBaselineFile(baseline);

        assertThat(mojo.enforceRatchet(resultWith(
            violation("com.day.cq.replication.Replicator", "core/src/main/java/foo/Activator.java", 10),
            violation("com.day.cq.replication.ReplicationOptions", "core/src/Other.java", 5))))
            .isNull();
    }

    @Test
    void lineShiftDoesNotMasqueradeAsNewViolation(@TempDir File dir) throws IOException, MojoFailureException {
        // Baseline recorded the finding at line 10; a refactor moved it to line 99.
        // Key excludes line numbers, so this must NOT fail the build.
        File baseline = writeBaseline(dir, LEGACY_KEY);
        DedeCheckMojo mojo = new DedeCheckMojo();
        mojo.setProjectPath(dir);
        mojo.setBaselineFile(baseline);

        assertThat(mojo.enforceRatchet(resultWith(
            violation("com.day.cq.replication.Replicator", "core/src/main/java/foo/Activator.java", 99))))
            .isNull();
    }

    @Test
    void updateBaselineWritesFindingsInsteadOfGating(@TempDir File dir) throws IOException, MojoFailureException {
        File baseline = new File(dir, "dede-baseline.json");
        DedeCheckMojo mojo = new DedeCheckMojo();
        mojo.setProjectPath(dir);
        mojo.setBaselineFile(baseline);
        mojo.setUpdateBaseline(true);

        assertThat(mojo.enforceRatchet(resultWith(
            violation("com.day.cq.replication.Replicator", "core/src/main/java/foo/Activator.java", 10),
            violation("com.day.cq.replication.Replicator", "core/src/main/java/foo/Activator.java", 42))))
            .isNull();

        assertThat(baseline).exists();
        String content = Files.readString(baseline.toPath());
        assertThat(content).contains(LEGACY_KEY); // deduped across both line hits
        assertThat(content).containsPattern("\"violationCount\"\\s*:\\s*1");
    }

    @Test
    void absoluteScanPathsMatchBaselineCommittedFromAnotherMachine(@TempDir File dir) throws IOException, MojoFailureException {
        // The scanner records absolute file paths (it walks from an absolute root).
        // CI simulates the reverse of local generation: baseline holds relative keys,
        // today's scan reports absolute paths under a different checkout directory.
        File baseline = writeBaseline(dir, LEGACY_KEY);
        DedeCheckMojo mojo = new DedeCheckMojo();
        mojo.setProjectPath(dir);
        mojo.setBaselineFile(baseline);

        String ciCheckoutPath = new File(dir, "core/src/main/java/foo/Activator.java").getAbsolutePath();

        assertThat(mojo.enforceRatchet(resultWith(
            violation("com.day.cq.replication.Replicator", ciCheckoutPath, 10))))
            .isNull();
    }

    @Test
    void keysAreDistinctAcrossReactorModulesUnderSharedRoot(@TempDir File dir) throws IOException, MojoFailureException {
        // Two modules with identical package/file layout under one multi-module root.
        // Anchoring relativization at the shared root keeps their keys distinct, so
        // fixing the violation in module A cannot mask module B's copy.
        File moduleA = new File(dir, "app-core/core/src/main/java/foo/Activator.java");
        File moduleB = new File(dir, "app-web/core/src/main/java/foo/Activator.java");
        File baselineFile = new File(dir, "dede-baseline.json");

        DedeCheckMojo writer = new DedeCheckMojo();
        writer.setProjectPath(new File(dir, "app-core"));
        writer.setMultiModuleRoot(dir);
        writer.setBaselineFile(baselineFile);
        writer.setUpdateBaseline(true);
        assertThat(writer.enforceRatchet(resultWith(
            violation("com.day.cq.replication.Replicator", moduleA.getAbsolutePath(), 10),
            violation("com.day.cq.replication.Replicator", moduleB.getAbsolutePath(), 10))))
            .isNull();

        String content = Files.readString(baselineFile.toPath());
        assertThat(content).containsPattern("\"violationCount\"\\s*:\\s*2");
        assertThat(content)
            .contains(DedeCheckMojo.baselineKey(
                violation("com.day.cq.replication.Replicator",
                    new File(dir, "app-core/core/src/main/java/foo/Activator.java").getAbsolutePath(), 10), dir.toPath()))
            .contains(DedeCheckMojo.baselineKey(
                violation("com.day.cq.replication.Replicator",
                    new File(dir, "app-web/core/src/main/java/foo/Activator.java").getAbsolutePath(), 10), dir.toPath()));

        // Now remove module A's violation: module B's copy must STILL be flagged as new
        // against that same baseline.
        DedeCheckMojo gate = new DedeCheckMojo();
        gate.setProjectPath(new File(dir, "app-web"));
        gate.setMultiModuleRoot(dir);
        gate.setBaselineFile(baselineFile);
        assertThat(gate.enforceRatchet(resultWith(
            violation("com.day.cq.replication.Replicator", moduleB.getAbsolutePath(), 10))))
            .isNull(); // B is baselined too -- both keys were written

        // ...while a genuinely new third occurrence fails:
        File moduleC = new File(dir, "app-forms/core/src/main/java/foo/Activator.java");
        assertThat(gate.enforceRatchet(resultWith(
            violation("com.day.cq.replication.Replicator", moduleB.getAbsolutePath(), 10),
            violation("com.day.cq.replication.Replicator", moduleC.getAbsolutePath(), 10))))
            .isNotNull()
            .contains("app-forms");
    }

    @Test
    void combinedGateFailureReportsBothGatesAtOnce() throws IOException, MojoFailureException {
        // Regression guard for fail-fast ordering: a CRITICAL cloud issue must not hide
        // ratchet findings (and vice versa) -- runAnalysis() joins both messages.
        File dir = Files.createTempDirectory("dede-combined").toFile();
        dir.deleteOnExit();
        File baseline = writeBaseline(dir, LEGACY_KEY);
        DedeCheckMojo mojo = new DedeCheckMojo();
        mojo.setFailOnCritical(true);
        mojo.setProjectPath(dir);
        mojo.setBaselineFile(baseline);

        CloudReadinessReport report = reportWith(new CloudIssue(Severity.CRITICAL, Category.DEPRECATED_API,
            "admin session usage", "Foo.java", 1));
        String criticalMsg = mojo.checkCritical(report);
        String ratchetMsg = mojo.enforceRatchet(resultWith(
            violation("com.day.cq.replication.ReplicationOptions", "core/src/New.java", 3)));

        assertThat(criticalMsg).isNotNull();
        assertThat(ratchetMsg).isNotNull();
        String combined = String.join("\n", List.of(criticalMsg, ratchetMsg));
        assertThat(combined)
            .contains("1 CRITICAL cloud-readiness issue")
            .contains("1 NEW forbidden-API violation")
            .contains("core/src/New.java");
    }
}
