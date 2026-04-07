package io.github.jasper.mybatis.encrypt.exception;

/**
 * 表示当前 SQL 行为命中了已声明的加密字段，但该操作语义当前不被支持。
 */
public class UnsupportedEncryptedOperationException extends RuntimeException {

    public UnsupportedEncryptedOperationException(String message) {
        super(message);
    }
}
