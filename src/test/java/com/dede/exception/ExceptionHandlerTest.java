package com.dede.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Exception Tests")
class ExceptionHandlerTest {

    @Test
    @DisplayName("DedeException should contain error code and message")
    void dedeExceptionShouldContainErrorCodeAndMessage() {
        DedeException exception = new DedeException(ErrorCode.INTERNAL_ERROR, "Test error message");

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(exception.getMessage()).isEqualTo("Test error message");
        assertThat(exception.getContext()).isNull();
    }

    @Test
    @DisplayName("DedeException should include context")
    void dedeExceptionShouldIncludeContext() {
        DedeException exception = new DedeException(ErrorCode.NODE_NOT_FOUND, "Node not found", "node:test");

        assertThat(exception.getContext()).isEqualTo("node:test");
        assertThat(exception.toString()).contains("node:test");
    }

    @Test
    @DisplayName("DedeException should preserve cause")
    void dedeExceptionShouldPreserveCause() {
        RuntimeException cause = new RuntimeException("Original error");
        DedeException exception = new DedeException(ErrorCode.INTERNAL_ERROR, "Wrapped error", cause);

        assertThat(exception.getCause()).isEqualTo(cause);
    }

    @Test
    @DisplayName("ParseException should include file path")
    void parseExceptionShouldIncludeFilePath() {
        Path filePath = Path.of("/src/Test.java");
        ParseException exception = new ParseException(ErrorCode.JAVA_PARSE_ERROR, "Syntax error", filePath);

        assertThat(exception.getFilePath()).isEqualTo(filePath);
        assertThat(exception.getLineNumber()).isEqualTo(-1);
    }

    @Test
    @DisplayName("ParseException should include line number")
    void parseExceptionShouldIncludeLineNumber() {
        Path filePath = Path.of("/src/Test.java");
        ParseException exception = new ParseException(ErrorCode.JAVA_PARSE_ERROR, "Syntax error", filePath, 42);

        assertThat(exception.getFilePath()).isEqualTo(filePath);
        assertThat(exception.getLineNumber()).isEqualTo(42);
        assertThat(exception.getContext()).contains("42");
    }

    @Test
    @DisplayName("ConfigurationException should include config key")
    void configurationExceptionShouldIncludeConfigKey() {
        ConfigurationException exception = new ConfigurationException(
            ErrorCode.PROFILE_NOT_FOUND,
            "Profile not found",
            "custom-profile"
        );

        assertThat(exception.getConfigKey()).isEqualTo("custom-profile");
    }

    @Test
    @DisplayName("GraphException should include node ID")
    void graphExceptionShouldIncludeNodeId() {
        GraphException exception = new GraphException(
            ErrorCode.NODE_NOT_FOUND,
            "Node not found",
            "class:MissingClass"
        );

        assertThat(exception.getNodeId()).isEqualTo("class:MissingClass");
    }

    @Test
    @DisplayName("ErrorCode should have code and description")
    void errorCodeShouldHaveCodeAndDescription() {
        assertThat(ErrorCode.JAVA_PARSE_ERROR.getCode()).isEqualTo("DEDE-1001");
        assertThat(ErrorCode.JAVA_PARSE_ERROR.getDescription()).isEqualTo("Failed to parse Java source file");
    }
}
