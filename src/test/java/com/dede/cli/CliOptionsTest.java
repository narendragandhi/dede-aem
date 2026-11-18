package com.dede.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CliOptionsTest {

    private static CliOptions parse(String... args) throws CliOptions.UsageException {
        return CliOptions.parse(args);
    }

    @Test
    void parsesValuesSwitchesAndRepeatableFlags() throws Exception {
        CliOptions o = parse("./proj", "--json", "r.json", "--fail-on", "high", "--security",
            "--request-log", "req.log", "--content", "a.zip", "--content", "https://aem/content/site",
            "--otel-max-classes", "20", "--mystery");

        assertThat(o.projectPath()).isEqualTo("./proj");
        assertThat(o.profiles()).isEqualTo("aem");
        assertThat(o.jsonOutputPath()).isEqualTo("r.json");
        assertThat(o.failOn()).isEqualTo("HIGH");
        assertThat(o.checkSecurity()).isTrue();
        assertThat(o.contentSources()).containsExactly("a.zip", "https://aem/content/site");
        assertThat(o.otelMaxClasses()).isEqualTo(20);
        assertThat(o.unknownFlags()).containsExactly("--mystery");
        assertThat(o.needsFindings()).isTrue();
    }

    @Test
    void failOnCriticalDoesNotOverrideAnExplicitLevel() throws Exception {
        assertThat(parse("p", "--fail-on", "MEDIUM", "--fail-on-critical").failOn()).isEqualTo("MEDIUM");
        assertThat(parse("p", "--fail-on-critical").failOn()).isEqualTo("CRITICAL");
        assertThat(parse("p").failOn()).isNull();
        assertThat(parse("p").needsFindings()).isFalse();
    }

    @Test
    void usageMistakesAreErrorsNotSilentlyIgnored() {
        assertThatThrownBy(() -> parse("p", "--json")).hasMessage("--json needs a value");
        assertThatThrownBy(() -> parse("p", "--json", "--security")).hasMessage("--json needs a value");
        assertThatThrownBy(() -> parse("p", "--fail-on", "SEVERE")).hasMessageStartingWith("--fail-on:");
        assertThatThrownBy(() -> parse("p", "--otel-max-classes", "lots")).hasMessageContaining("not a number");
        assertThatThrownBy(() -> parse("p", "--otel-max-classes", "0")).hasMessageContaining("at least 1");
        assertThatThrownBy(() -> parse("p", "--otel-config", "o.properties")).hasMessageContaining("needs --runtime-traces");
        assertThatThrownBy(() -> parse("p", "--mcp", "--watch")).hasMessageContaining("can't be combined");
        assertThatThrownBy(() -> parse("--json", "r.json")).hasMessageContaining("project path");
    }

    @Test
    void everyParsedFlagIsAKnownFlag() {
        assertThat(CliOptions.KNOWN_FLAGS).contains("--content", "--error-log", "--mcp", "--help", "--fail-on-critical");
    }
}
