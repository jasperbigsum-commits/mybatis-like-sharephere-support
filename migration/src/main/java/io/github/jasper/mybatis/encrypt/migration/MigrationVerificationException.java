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
        this(MigrationErrorCode.VERIFICATION_VALUE_MISMATCH, message);
    }

    /**
     * Create a verification exception with a structured error code.
     *
     * @param errorCode structured error code
     * @param message error message
     */
    public MigrationVerificationException(MigrationErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
