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

    /**
     * 默认替换字符。
     */
    protected static final char DEFAULT_REPLACE_CHAR = '*';

    /**
     * 对输入做预处理，子类可按需重写。
     *
     * @param plainText 原始明文
     * @return 预处理后的明文
     */
    protected String normalizeInput(String plainText) {
        return plainText;
    }

    /**
     * 将预处理后的明文转为字符数组。
     *
     * @param plainText 原始明文
     * @return 字符数组；为空时返回 {@code null}
     */
    protected char[] toChars(String plainText) {
        String normalized = normalizeInput(plainText);
        return null == normalized ? null : normalized.toCharArray();
    }

    /**
     * 将字符数组转回字符串。
     *
     * @param chars 字符数组
     * @param original 原始明文
     * @return 转换后的字符串
     */
    protected String asString(char[] chars, String original) {
        return null == chars ? null : new String(chars);
    }

    /**
     * 校验给定参数必须大于等于 0。
     *
     * @param name 参数名
     * @param value 参数值
     */
    protected static void requireNonNegative(String name, int value) {
        if (value < 0) {
            throw new EncryptionConfigurationException(EncryptionErrorCode.INVALID_FIELD_RULE,
                    name + " must be greater than or equal to 0.");
        }
    }

    /**
     * 校验区间起点不能大于终点。
     *
     * @param fromX 区间起点
     * @param toY 区间终点
     */
    protected static void requireRange(int fromX, int toY) {
        if (fromX > toY) {
            throw new EncryptionConfigurationException(EncryptionErrorCode.INVALID_FIELD_RULE,
                    "fromX must be less than or equal to toY.");
        }
    }
}
