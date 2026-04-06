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
 * Decrypts MyBatis query results in place.
 *
 * <p>The decryptor only handles entity types that have registered encryption metadata.
 * Plain maps and unrelated result objects are ignored.</p>
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
     * Decrypts a single result object or a collection result returned by MyBatis.
     *
     * @param resultObject query result
     * @return the same instance after decryption and optional separate-table hydration
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
     * Decrypts encrypted properties for a single entity instance.
     *
     * @param candidate entity instance returned by MyBatis
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
