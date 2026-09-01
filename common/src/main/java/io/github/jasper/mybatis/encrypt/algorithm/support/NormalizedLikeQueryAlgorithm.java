package io.github.jasper.mybatis.encrypt.algorithm.support;

import java.util.Locale;
import io.github.jasper.mybatis.encrypt.algorithm.LikeQueryAlgorithm;

/**
 * 基于文本规范化的 LIKE 查询辅助算法实现。
 *
 * <p>将明文做 trim + lowercase 处理后存入辅助列，配合数据库 LIKE 语法实现大小写不敏感的模糊匹配。
 * 注意：该实现以明文形式存储规范化后的值，不提供加密保护，仅适用于对模糊查询列安全性要求较低的场景。</p>
 */
public class NormalizedLikeQueryAlgorithm implements LikeQueryAlgorithm {

    @Override
    public String transform(String plainText) {
        if (plainText == null) {
            return null;
        }
        return plainText.trim().toLowerCase(Locale.ROOT);
    }
}
