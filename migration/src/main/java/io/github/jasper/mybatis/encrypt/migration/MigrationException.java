package io.github.jasper.mybatis.encrypt.migration;

/**
 * Migration module base exception.
 */
public class MigrationException extends RuntimeException {

    /**
     * Create a migration exception with a message only.
     *
     * @param message error message
     */
    public MigrationException(String message) {
        super(message);
    }

    /**
     * Create a migration exception with a message and cause.
     *
     * @param message error message
     * @param cause root cause
     */
    public MigrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
