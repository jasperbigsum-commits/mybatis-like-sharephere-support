package io.github.jasper.mybatis.encrypt.migration;

/**
 * Raised when another process or thread already holds the checkpoint lock for the same migration task.
 */
public class MigrationCheckpointLockException extends MigrationException {

    /**
     * Create a checkpoint lock exception.
     *
     * @param errorCode structured error code
     * @param message error message
     */
    public MigrationCheckpointLockException(MigrationErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    /**
     * Create a checkpoint lock exception.
     *
     * @param errorCode structured error code
     * @param message error message
     * @param cause root cause
     */
    public MigrationCheckpointLockException(MigrationErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
