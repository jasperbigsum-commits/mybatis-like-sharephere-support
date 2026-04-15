package io.github.jasper.mybatis.encrypt.migration;

/**
 * Migration module base exception.
 */
public class MigrationException extends RuntimeException {

    private final MigrationErrorCode errorCode;

    /**
     * Create a migration exception with a message only.
     *
     * @param message error message
     */
    public MigrationException(String message) {
        this(MigrationErrorCode.GENERAL_FAILURE, message, null);
    }

    /**
     * Create a migration exception with a message and cause.
     *
     * @param message error message
     * @param cause root cause
     */
    public MigrationException(String message, Throwable cause) {
        this(MigrationErrorCode.GENERAL_FAILURE, message, cause);
    }

    /**
     * Create a migration exception with a structured error code.
     *
     * @param errorCode structured error code
     * @param message error message
     */
    public MigrationException(MigrationErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    /**
     * Create a migration exception with a structured error code and cause.
     *
     * @param errorCode structured error code
     * @param message error message
     * @param cause root cause
     */
    public MigrationException(MigrationErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode == null ? MigrationErrorCode.GENERAL_FAILURE : errorCode;
    }

    /**
     * Return the structured error code for application-side handling.
     *
     * @return migration error code
     */
    public MigrationErrorCode getErrorCode() {
        return errorCode;
    }
}
