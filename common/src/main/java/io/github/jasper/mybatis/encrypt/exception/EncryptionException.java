package io.github.jasper.mybatis.encrypt.exception;

/**
 * Base runtime exception for encryption configuration, rewrite and hydration flows.
 */
public class EncryptionException extends RuntimeException {

    private final EncryptionErrorCode errorCode;

    /**
     * Create an exception with a structured error code.
     *
     * @param errorCode structured error code
     * @param message error message
     */
    public EncryptionException(EncryptionErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    /**
     * Create an exception with a structured error code and cause.
     *
     * @param errorCode structured error code
     * @param message error message
     * @param cause root cause
     */
    public EncryptionException(EncryptionErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode == null ? EncryptionErrorCode.GENERAL_FAILURE : errorCode;
    }

    /**
     * Return the structured runtime error code.
     *
     * @return encryption runtime error code
     */
    public EncryptionErrorCode getErrorCode() {
        return errorCode;
    }
}
