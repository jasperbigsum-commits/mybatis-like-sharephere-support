package io.github.jasper.mybatis.encrypt.algorithm.support;

import io.github.jasper.mybatis.encrypt.algorithm.LikeQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.exception.EncryptionConfigurationException;
import io.github.jasper.mybatis.encrypt.exception.EncryptionErrorCode;

/**
 * 基于字符覆盖的 LIKE 预处理算法基类。
 *
 * <p>这类算法主要参考 Apache ShardingSphere 的 mask cover 算法语义，
 * 但在本项目中实现为 {@link LikeQueryAlgorithm}，用于统一参与写入和查询侧的值转换。</p>
 */
abstract class AbstractMaskLikeQueryAlgorithm implements LikeQueryAlgorithm {

    protected static final char DEFAULT_REPLACE_CHAR = '*';

    protected String normalizeInput(String plainText) {
        return plainText;
    }

    protected char[] toChars(String plainText) {
        String normalized = normalizeInput(plainText);
        return null == normalized ? null : normalized.toCharArray();
    }

    protected String asString(char[] chars, String original) {
        return null == chars ? null : new String(chars);
    }

    protected static void requireNonNegative(String name, int value) {
        if (value < 0) {
            throw new EncryptionConfigurationException(EncryptionErrorCode.INVALID_FIELD_RULE,
                    name + " must be greater than or equal to 0.");
        }
    }

    protected static void requireRange(int fromX, int toY) {
        if (fromX > toY) {
            throw new EncryptionConfigurationException(EncryptionErrorCode.INVALID_FIELD_RULE,
                    "fromX must be less than or equal to toY.");
        }
    }
}
