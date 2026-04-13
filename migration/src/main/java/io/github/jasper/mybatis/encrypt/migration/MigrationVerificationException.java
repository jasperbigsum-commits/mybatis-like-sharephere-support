package io.github.jasper.mybatis.encrypt.migration;

/**
 * Raised when post-write verification detects inconsistent migrated data.
 */
public class MigrationVerificationException extends MigrationException {

    /**
     * Create a verification exception with a message only.
     *
     * @param message error message
     */
    public MigrationVerificationException(String message) {
        super(message);
    }
}
