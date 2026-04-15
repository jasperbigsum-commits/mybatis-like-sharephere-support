package io.github.jasper.mybatis.encrypt.migration;

/**
 * Raised when checkpoint state files cannot be loaded or persisted safely.
 */
public class MigrationStateStoreException extends MigrationException {

    /**
     * Create a state store exception with a structured error code.
     *
     * @param errorCode structured error code
     * @param message error message
     */
    public MigrationStateStoreException(MigrationErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    /**
     * Create a state store exception with a structured error code and cause.
     *
     * @param errorCode structured error code
     * @param message error message
     * @param cause root cause
     */
    public MigrationStateStoreException(MigrationErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
