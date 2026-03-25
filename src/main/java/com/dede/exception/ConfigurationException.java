package com.dede.exception;

/**
 * Exception thrown when configuration is invalid or missing.
 */
public class ConfigurationException extends DedeException {

    private final String configKey;

    public ConfigurationException(ErrorCode errorCode, String message) {
        super(errorCode, message);
        this.configKey = null;
    }

    public ConfigurationException(ErrorCode errorCode, String message, String configKey) {
        super(errorCode, message, configKey);
        this.configKey = configKey;
    }

    public ConfigurationException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
        this.configKey = null;
    }

    public ConfigurationException(ErrorCode errorCode, String message, String configKey, Throwable cause) {
        super(errorCode, message, configKey, cause);
        this.configKey = configKey;
    }

    public String getConfigKey() {
        return configKey;
    }
}
