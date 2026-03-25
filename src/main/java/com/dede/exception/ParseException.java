package com.dede.exception;

import java.nio.file.Path;

/**
 * Exception thrown when parsing fails for any supported file type.
 */
public class ParseException extends DedeException {

    private final Path filePath;
    private final int lineNumber;

    public ParseException(ErrorCode errorCode, String message, Path filePath) {
        super(errorCode, message, filePath != null ? filePath.toString() : null);
        this.filePath = filePath;
        this.lineNumber = -1;
    }

    public ParseException(ErrorCode errorCode, String message, Path filePath, Throwable cause) {
        super(errorCode, message, filePath != null ? filePath.toString() : null, cause);
        this.filePath = filePath;
        this.lineNumber = -1;
    }

    public ParseException(ErrorCode errorCode, String message, Path filePath, int lineNumber) {
        super(errorCode, message, filePath + ":" + lineNumber);
        this.filePath = filePath;
        this.lineNumber = lineNumber;
    }

    public ParseException(ErrorCode errorCode, String message, Path filePath, int lineNumber, Throwable cause) {
        super(errorCode, message, filePath + ":" + lineNumber, cause);
        this.filePath = filePath;
        this.lineNumber = lineNumber;
    }

    public Path getFilePath() {
        return filePath;
    }

    public int getLineNumber() {
        return lineNumber;
    }
}
