package com.dede.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Global exception handler for REST API endpoints.
 * Converts exceptions to structured JSON error responses.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DedeException.class)
    public ResponseEntity<Map<String, Object>> handleDedeException(DedeException ex) {
        log.error("Dede error: {} - {}", ex.getErrorCode().getCode(), ex.getMessage(), ex);

        HttpStatus status = mapErrorCodeToStatus(ex.getErrorCode());
        return ResponseEntity.status(status).body(buildErrorResponse(ex, status));
    }

    @ExceptionHandler(ParseException.class)
    public ResponseEntity<Map<String, Object>> handleParseException(ParseException ex) {
        log.error("Parse error in {}: {}", ex.getFilePath(), ex.getMessage(), ex);

        Map<String, Object> response = buildErrorResponse(ex, HttpStatus.BAD_REQUEST);
        if (ex.getFilePath() != null) {
            response.put("filePath", ex.getFilePath().toString());
        }
        if (ex.getLineNumber() > 0) {
            response.put("lineNumber", ex.getLineNumber());
        }

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(ConfigurationException.class)
    public ResponseEntity<Map<String, Object>> handleConfigurationException(ConfigurationException ex) {
        log.error("Configuration error: {}", ex.getMessage(), ex);

        Map<String, Object> response = buildErrorResponse(ex, HttpStatus.INTERNAL_SERVER_ERROR);
        if (ex.getConfigKey() != null) {
            response.put("configKey", ex.getConfigKey());
        }

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    @ExceptionHandler(GraphException.class)
    public ResponseEntity<Map<String, Object>> handleGraphException(GraphException ex) {
        log.error("Graph error: {}", ex.getMessage(), ex);

        HttpStatus status = ex.getErrorCode() == ErrorCode.NODE_NOT_FOUND
            ? HttpStatus.NOT_FOUND
            : HttpStatus.INTERNAL_SERVER_ERROR;

        Map<String, Object> response = buildErrorResponse(ex, status);
        if (ex.getNodeId() != null) {
            response.put("nodeId", ex.getNodeId());
        }

        return ResponseEntity.status(status).body(response);
    }

    /**
     * NoResourceFoundException is Spring's own signal for "no route/static
     * resource matched this URL" -- a genuine 404, not a server bug. Without
     * this handler, the generic Exception.class handler below catches it too
     * (RestControllerAdvice applies globally) and every unmapped URL returns
     * 500 "Internal Server Error" instead of 404. Confirmed by hitting a
     * nonexistent endpoint directly.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResourceFound(NoResourceFoundException ex) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("timestamp", Instant.now().toString());
        response.put("status", HttpStatus.NOT_FOUND.value());
        response.put("error", HttpStatus.NOT_FOUND.getReasonPhrase());
        response.put("errorCode", ErrorCode.FILE_NOT_FOUND.getCode());
        response.put("message", "No endpoint found for this URL");

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("timestamp", Instant.now().toString());
        response.put("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
        response.put("error", HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase());
        response.put("errorCode", ErrorCode.INTERNAL_ERROR.getCode());
        response.put("message", "An unexpected error occurred");

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    private Map<String, Object> buildErrorResponse(DedeException ex, HttpStatus status) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("timestamp", Instant.now().toString());
        response.put("status", status.value());
        response.put("error", status.getReasonPhrase());
        response.put("errorCode", ex.getErrorCode().getCode());
        response.put("message", ex.getMessage());
        if (ex.getContext() != null) {
            response.put("context", ex.getContext());
        }
        return response;
    }

    private HttpStatus mapErrorCodeToStatus(ErrorCode errorCode) {
        return switch (errorCode) {
            case FILE_NOT_FOUND, NODE_NOT_FOUND, PROFILE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case VALIDATION_ERROR, GOVERNANCE_VIOLATION, INVALID_PROFILE -> HttpStatus.BAD_REQUEST;
            case PARSE_ERROR, JAVA_PARSE_ERROR, MANIFEST_PARSE_ERROR,
                 HTL_PARSE_ERROR, JCR_PARSE_ERROR, DISPATCHER_PARSE_ERROR,
                 PROFILE_PARSE_ERROR, RULES_PARSE_ERROR -> HttpStatus.UNPROCESSABLE_ENTITY;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
