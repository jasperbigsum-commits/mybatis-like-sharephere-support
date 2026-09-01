package io.github.jasper.mybatis.encrypt.migration;

/**
 * Raised when a cursor value or checkpoint shape cannot be used safely.
 */
public class MigrationCursorException extends MigrationException {

    /**
     * Create a cursor exception with a structured error code.
     *
     * @param errorCode structured error code
     * @param message error message
     */
    public MigrationCursorException(MigrationErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
