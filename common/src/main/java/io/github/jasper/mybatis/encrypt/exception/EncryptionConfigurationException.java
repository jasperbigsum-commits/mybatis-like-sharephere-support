package io.github.jasper.mybatis.encrypt.exception;

/**
 * 表示插件配置不完整或算法初始化失败。
 */
public class EncryptionConfigurationException extends RuntimeException {

    /**
     * 使用错误消息创建异常。
     *
     * @param message 错误消息
     */
    public EncryptionConfigurationException(String message) {
        super(message);
    }

    /**
     * 使用错误消息和根因创建异常。
     *
     * @param message 错误消息
     * @param cause 根因异常
     */
    public EncryptionConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
