package com.dede.exception;

/**
 * Enumeration of error codes for structured error handling.
 */
public enum ErrorCode {

    // Parsing errors (1xxx)
    PARSE_ERROR("DEDE-1000", "General parsing error"),
    JAVA_PARSE_ERROR("DEDE-1001", "Failed to parse Java source file"),
    MANIFEST_PARSE_ERROR("DEDE-1002", "Failed to parse OSGi manifest"),
    HTL_PARSE_ERROR("DEDE-1003", "Failed to parse HTL template"),
    JCR_PARSE_ERROR("DEDE-1004", "Failed to parse JCR content"),
    DISPATCHER_PARSE_ERROR("DEDE-1005", "Failed to parse Dispatcher configuration"),
    PROFILE_PARSE_ERROR("DEDE-1006", "Failed to parse profile configuration"),
    RULES_PARSE_ERROR("DEDE-1007", "Failed to parse governance rules"),

    // File I/O errors (2xxx)
    FILE_NOT_FOUND("DEDE-2000", "File not found"),
    FILE_READ_ERROR("DEDE-2001", "Failed to read file"),
    FILE_WRITE_ERROR("DEDE-2002", "Failed to write file"),
    DIRECTORY_SCAN_ERROR("DEDE-2003", "Failed to scan directory"),

    // Configuration errors (3xxx)
    CONFIG_ERROR("DEDE-3000", "Configuration error"),
    PROFILE_NOT_FOUND("DEDE-3001", "Profile not found"),
    INVALID_PROFILE("DEDE-3002", "Invalid profile format"),

    // Graph errors (4xxx)
    GRAPH_ERROR("DEDE-4000", "Graph operation error"),
    NODE_NOT_FOUND("DEDE-4001", "Node not found in graph"),
    CYCLE_DETECTED("DEDE-4002", "Circular dependency detected"),
    EXPORT_ERROR("DEDE-4003", "Failed to export graph"),

    // Validation errors (5xxx)
    VALIDATION_ERROR("DEDE-5000", "Validation error"),
    GOVERNANCE_VIOLATION("DEDE-5001", "Governance rule violation"),
    SECURITY_VIOLATION("DEDE-5002", "Security policy violation"),

    // System errors (9xxx)
    INTERNAL_ERROR("DEDE-9000", "Internal system error"),
    UNSUPPORTED_OPERATION("DEDE-9001", "Unsupported operation");

    private final String code;
    private final String description;

    ErrorCode(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }
}
