package io.github.jasper.mybatis.encrypt.exception;

/**
 * 表示当前 SQL 行为命中了已声明的加密字段，但该操作语义当前不被支持。
 */
public class UnsupportedEncryptedOperationException extends EncryptionException {

    /**
     * 使用错误消息创建异常。
     *
     * @param message 错误消息
     */
    public UnsupportedEncryptedOperationException(String message) {
        this(EncryptionErrorCode.UNSUPPORTED_ENCRYPTED_OPERATION, message);
    }

    /**
     * 使用结构化错误码创建异常。
     *
     * @param errorCode 错误码
     * @param message 错误消息
     */
    public UnsupportedEncryptedOperationException(EncryptionErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
