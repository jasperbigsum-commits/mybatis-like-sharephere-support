package tech.jasper.mybatis.encrypt.core.decrypt;

import java.util.Collection;
import java.util.Map;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import tech.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import tech.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import tech.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import tech.jasper.mybatis.encrypt.core.metadata.EncryptTableRule;
import tech.jasper.mybatis.encrypt.core.support.SeparateTableEncryptionManager;

/**
 * 查询结果解密器。
 *
 * <p>只对声明过加密规则的实体对象执行字段解密，避免对普通 DTO、Map 或无关对象做误处理。
 * 当前实现按对象属性进行原地修改，因此要求结果对象具备可读写属性。</p>
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
     * 对查询结果做统一解密。
     *
     * <p>支持单对象和集合结果，集合场景会逐个元素处理。</p>
     *
     * @param resultObject MyBatis 返回的结果对象
     * @return 解密后的原对象
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
