package io.github.jasper.mybatis.encrypt.core.rewrite;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.algorithm.AssistedQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.CipherAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.LikeQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import io.github.jasper.mybatis.encrypt.exception.EncryptionConfigurationException;

/**
 * 加密字段值转换器。
 *
 * <p>它把“业务明文 -> 具体查询/存储态值”的转换逻辑独立出来，让 `SqlRewriteEngine`
 * 只负责 AST 改写与参数重建，不直接关心算法选择细节。</p>
 */
final class EncryptionValueTransformer {

    private final AlgorithmRegistry algorithmRegistry;

    EncryptionValueTransformer(AlgorithmRegistry algorithmRegistry) {
        this.algorithmRegistry = algorithmRegistry;
    }

    String transformCipher(EncryptColumnRule rule, Object plainValue) {
        return applyTransform(rule, plainValue, algorithmRegistry.cipher(rule.cipherAlgorithm()));
    }

    String transformAssisted(EncryptColumnRule rule, Object plainValue) {
        return applyTransform(rule, plainValue, algorithmRegistry.assisted(rule.assistedQueryAlgorithm()));
    }

    String transformLike(EncryptColumnRule rule, Object plainValue) {
        return applyTransform(rule, plainValue, algorithmRegistry.like(rule.likeQueryAlgorithm()));
    }

    private String applyTransform(EncryptColumnRule rule, Object plainValue, Object algorithm) {
        if (plainValue == null) {
            return null;
        }
        String value = String.valueOf(plainValue);
        if (algorithm instanceof CipherAlgorithm) {
            return ((CipherAlgorithm) algorithm).encrypt(value);
        }
        if (algorithm instanceof AssistedQueryAlgorithm) {
            return ((AssistedQueryAlgorithm) algorithm).transform(value);
        }
        if (algorithm instanceof LikeQueryAlgorithm) {
            return ((LikeQueryAlgorithm) algorithm).transform(value);
        }
        throw new EncryptionConfigurationException("Unsupported algorithm for field: " + rule.property());
    }
}
