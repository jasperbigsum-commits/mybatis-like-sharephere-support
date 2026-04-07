package io.github.jasper.mybatis.encrypt.core.decrypt;

import java.util.Collection;
import java.util.Map;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptTableRule;
import io.github.jasper.mybatis.encrypt.core.support.SeparateTableEncryptionManager;

/**
 * 原地解密 MyBatis 查询结果。
 *
 * <p>只处理已经注册加密元数据的实体类型，普通 Map 和无关结果对象会被直接忽略。</p>
 */
public class ResultDecryptor {

    private final EncryptMetadataRegistry metadataRegistry;
    private final AlgorithmRegistry algorithmRegistry;
    private final SeparateTableEncryptionManager separateTableEncryptionManager;

    public ResultDecryptor(EncryptMetadataRegistry metadataRegistry,
                           AlgorithmRegistry algorithmRegistry,
                           SeparateTableEncryptionManager separateTableEncryptionManager) {
        this.metadataRegistry = metadataRegistry;
        this.algorithmRegistry = algorithmRegistry;
        this.separateTableEncryptionManager = separateTableEncryptionManager;
    }

    /**
     * 解密 MyBatis 返回的单个结果对象或结果集合。
     *
     * @param resultObject 查询结果对象
     * @return 完成解密和可选独立表回填后的同一个实例
     */
    public Object decrypt(Object resultObject) {
        if (resultObject == null) {
            return null;
        }
        if (separateTableEncryptionManager != null) {
            separateTableEncryptionManager.hydrateResults(resultObject);
        }
        if (resultObject instanceof Collection<?> collection) {
            collection.forEach(this::decryptSingle);
            return resultObject;
        }
        decryptSingle(resultObject);
        return resultObject;
    }

    /**
     * 解密单个实体实例上的加密属性。
     *
     * @param candidate MyBatis 返回的实体实例
     */
    private void decryptSingle(Object candidate) {
        if (candidate == null || candidate instanceof Map<?, ?>) {
            return;
        }
        EncryptTableRule tableRule = metadataRegistry.findByEntity(candidate.getClass()).orElse(null);
        if (tableRule == null) {
            return;
        }
        MetaObject metaObject = SystemMetaObject.forObject(candidate);
        for (EncryptColumnRule rule : tableRule.getColumnRules()) {
            if (!metaObject.hasGetter(rule.property()) || !metaObject.hasSetter(rule.property())) {
                continue;
            }
            if (rule.isStoredInSeparateTable()) {
                continue;
            }
            Object value = metaObject.getValue(rule.property());
            if (!(value instanceof String cipherText) || cipherText.isBlank()) {
                continue;
            }
            metaObject.setValue(rule.property(), algorithmRegistry.cipher(rule.cipherAlgorithm()).decrypt(cipherText));
        }
    }
}
