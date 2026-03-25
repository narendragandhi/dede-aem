package com.dede.exception;

/**
 * Base exception for all Dede-specific errors.
 * Provides structured error handling with error codes and contextual information.
 */
public class DedeException extends RuntimeException {

    private final ErrorCode errorCode;
    private final String context;

    public DedeException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.context = null;
    }

    public DedeException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.context = null;
    }

    public DedeException(ErrorCode errorCode, String message, String context) {
        super(message);
        this.errorCode = errorCode;
        this.context = context;
    }

    public DedeException(ErrorCode errorCode, String message, String context, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.context = context;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public String getContext() {
        return context;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(errorCode.getCode()).append("] ").append(getMessage());
        if (context != null) {
            sb.append(" (context: ").append(context).append(")");
        }
        return sb.toString();
    }
}
