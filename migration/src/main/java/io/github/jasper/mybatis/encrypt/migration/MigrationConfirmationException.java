package io.github.jasper.mybatis.encrypt.migration;

/**
 * Raised when operator confirmation is missing, stale or unreadable.
 */
public class MigrationConfirmationException extends MigrationException {

    /**
     * Create a confirmation exception with a structured error code.
     *
     * @param errorCode structured error code
     * @param message error message
     */
    public MigrationConfirmationException(MigrationErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    /**
     * Create a confirmation exception with a structured error code and cause.
     *
     * @param errorCode structured error code
     * @param message error message
     * @param cause root cause
     */
    public MigrationConfirmationException(MigrationErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
