package io.github.jasper.mybatis.encrypt.migration;

/**
 * Raised when JDBC execution or range refresh fails during migration.
 */
public class MigrationExecutionException extends MigrationException {

    /**
     * Create an execution exception with a structured error code.
     *
     * @param errorCode structured error code
     * @param message error message
     * @param cause root cause
     */
    public MigrationExecutionException(MigrationErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
