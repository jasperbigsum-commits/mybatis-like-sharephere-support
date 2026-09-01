package io.github.jasper.mybatis.encrypt.migration;

/**
 * Raised when the requested migration target or field selection is invalid.
 */
public class MigrationDefinitionException extends MigrationException {

    /**
     * Create a definition exception with a structured error code.
     *
     * @param errorCode structured error code
     * @param message error message
     */
    public MigrationDefinitionException(MigrationErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    /**
     * Create a definition exception with a structured error code and cause.
     *
     * @param errorCode structured error code
     * @param message error message
     * @param cause root cause
     */
    public MigrationDefinitionException(MigrationErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
