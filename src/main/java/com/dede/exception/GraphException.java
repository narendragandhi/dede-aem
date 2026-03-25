package com.dede.exception;

/**
 * Exception thrown when graph operations fail.
 */
public class GraphException extends DedeException {

    private final String nodeId;

    public GraphException(ErrorCode errorCode, String message) {
        super(errorCode, message);
        this.nodeId = null;
    }

    public GraphException(ErrorCode errorCode, String message, String nodeId) {
        super(errorCode, message, nodeId);
        this.nodeId = nodeId;
    }

    public GraphException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
        this.nodeId = null;
    }

    public GraphException(ErrorCode errorCode, String message, String nodeId, Throwable cause) {
        super(errorCode, message, nodeId, cause);
        this.nodeId = nodeId;
    }

    public String getNodeId() {
        return nodeId;
    }
}
