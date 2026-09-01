package io.github.jasper.mybatis.encrypt.exception;

/**
 * 表示插件配置不完整或算法初始化失败。
 */
public class EncryptionConfigurationException extends EncryptionException {

    /**
     * 使用错误消息创建异常。
     *
     * @param message 错误消息
     */
    public EncryptionConfigurationException(String message) {
        this(EncryptionErrorCode.GENERAL_FAILURE, message, null);
    }

    /**
     * 使用错误消息和根因创建异常。
     *
     * @param message 错误消息
     * @param cause 根因异常
     */
    public EncryptionConfigurationException(String message, Throwable cause) {
        this(EncryptionErrorCode.GENERAL_FAILURE, message, cause);
    }

    /**
     * 使用结构化错误码和错误消息创建异常。
     *
     * @param errorCode 错误码
     * @param message 错误消息
     */
    public EncryptionConfigurationException(EncryptionErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    /**
     * 使用结构化错误码、错误消息和根因创建异常。
     *
     * @param errorCode 错误码
     * @param message 错误消息
     * @param cause 根因异常
     */
    public EncryptionConfigurationException(EncryptionErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
