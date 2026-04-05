package tech.jasper.mybatis.encrypt.exception;

/**
 * 表示插件配置不完整或算法初始化失败。
 */
public class EncryptionConfigurationException extends RuntimeException {

    public EncryptionConfigurationException(String message) {
        super(message);
    }

    public EncryptionConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
