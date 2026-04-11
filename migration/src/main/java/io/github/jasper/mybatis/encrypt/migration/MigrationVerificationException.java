package io.github.jasper.mybatis.encrypt.migration;

/**
 * Raised when post-write verification detects inconsistent migrated data.
 */
public class MigrationVerificationException extends MigrationException {

    public MigrationVerificationException(String message) {
        super(message);
    }
}
