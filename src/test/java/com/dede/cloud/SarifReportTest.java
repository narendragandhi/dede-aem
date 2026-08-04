package com.dede.cloud;

import com.dede.cloud.ForbiddenApiScanner.ForbiddenApiViolation;
import com.dede.cloud.ForbiddenApiScanner.ViolationType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cweId-present branch of buildRule() had never been exercised by a test: it
 * threw NullPointerException from Map.of("guid", null, ...) -- Map.of() forbids null
 * values -- for every violation with a mapped CWE. Only surfaced when the new Maven
 * plugin's SARIF export hit a violation with a non-null cweId for the first time.
 */
@SuppressWarnings("unchecked")
class SarifReportTest {

    private static final Path ROOT = Path.of("/project");

    @Test
    void violationWithoutCweProducesValidRule() {
        var violation = new ForbiddenApiViolation(
            "/project/src/Foo.java", 10, ViolationType.FORBIDDEN_METHOD_CALL, "System.out.println",
            "CONSOLE_OUTPUT", ForbiddenApiCatalog.Severity.MEDIUM,
            "Direct console output", "Use SLF4J"
        );

        Map<String, Object> sarif = new SarifReport(List.of(violation), ROOT).toSarif();

        assertThat(sarif).containsEntry("version", "2.1.0");
        Map<String, Object> run = (Map<String, Object>) ((List<Object>) sarif.get("runs")).get(0);
        List<Object> results = (List<Object>) run.get("results");
        assertThat(results).hasSize(1);
    }

    @Test
    void violationWithCweDoesNotThrowAndProducesValidRule() {
        var violation = new ForbiddenApiViolation(
            "/project/src/Bad.java", 42, ViolationType.FORBIDDEN_METHOD_CALL, "loginAdministrative",
            "ADMIN_RESOLVER", ForbiddenApiCatalog.Severity.CRITICAL,
            "Uses admin resolver", "Use service users", "CWE-269", "Improper Privilege Management"
        );

        Map<String, Object> sarif = new SarifReport(List.of(violation), ROOT).toSarif();

        Map<String, Object> run = (Map<String, Object>) ((List<Object>) sarif.get("runs")).get(0);
        Map<String, Object> tool = (Map<String, Object>) run.get("tool");
        Map<String, Object> driver = (Map<String, Object>) tool.get("driver");
        List<Object> rules = (List<Object>) driver.get("rules");
        assertThat(rules).hasSize(1);

        Map<String, Object> rule = (Map<String, Object>) rules.get(0);
        assertThat(rule).containsKey("relationships");
        List<Object> relationships = (List<Object>) rule.get("relationships");
        Map<String, Object> relationship = (Map<String, Object>) relationships.get(0);
        Map<String, Object> target = (Map<String, Object>) relationship.get("target");
        assertThat(target).containsEntry("id", "CWE-269");
        assertThat(target).doesNotContainKey("guid");
    }

    @Test
    void emptyViolationListProducesEmptyResults() {
        Map<String, Object> sarif = new SarifReport(List.of(), ROOT).toSarif();

        Map<String, Object> run = (Map<String, Object>) ((List<Object>) sarif.get("runs")).get(0);
        assertThat((List<Object>) run.get("results")).isEmpty();
    }
}
