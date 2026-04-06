package tech.jasper.mybatis.encrypt.core.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tech.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;

class EncryptMetadataRegistryTest {

    @Test
    void shouldInferSourceIdPropertyFromConfiguredSourceIdColumn() {
        DatabaseEncryptionProperties properties = new DatabaseEncryptionProperties();
        DatabaseEncryptionProperties.TableRuleProperties tableRule = new DatabaseEncryptionProperties.TableRuleProperties();
        tableRule.setTable("user_phone_encrypt");

        DatabaseEncryptionProperties.FieldRuleProperties fieldRule = new DatabaseEncryptionProperties.FieldRuleProperties();
        fieldRule.setColumn("phone");
        fieldRule.setStorageMode(FieldStorageMode.SEPARATE_TABLE);
        fieldRule.setStorageTable("user_phone_encrypt_store");
        fieldRule.setSourceIdColumn("tenant_user_id");
        fieldRule.setAssistedQueryColumn("phone_hash");
        tableRule.getFields().put("phone", fieldRule);
        properties.getTables().put("userPhoneEncrypt", tableRule);

        EncryptMetadataRegistry registry = new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader());
        EncryptColumnRule rule = registry.findByTable("user_phone_encrypt")
                .orElseThrow()
                .findByProperty("phone")
                .orElseThrow();

        assertEquals("tenant_user_id", rule.sourceIdColumn());
        assertEquals("tenantUserId", rule.sourceIdProperty());
        assertEquals("tenant_user_id", rule.storageIdColumn());
    }

    @Test
    void shouldFallbackToIdWhenConfiguredSourceIdIsOmitted() {
        DatabaseEncryptionProperties properties = new DatabaseEncryptionProperties();
        DatabaseEncryptionProperties.TableRuleProperties tableRule = new DatabaseEncryptionProperties.TableRuleProperties();
        tableRule.setTable("user_phone_encrypt");

        DatabaseEncryptionProperties.FieldRuleProperties fieldRule = new DatabaseEncryptionProperties.FieldRuleProperties();
        fieldRule.setColumn("phone");
        fieldRule.setStorageMode(FieldStorageMode.SEPARATE_TABLE);
        fieldRule.setStorageTable("user_phone_encrypt_store");
        fieldRule.setAssistedQueryColumn("phone_hash");
        tableRule.getFields().put("phone", fieldRule);
        properties.getTables().put("userPhoneEncrypt", tableRule);

        EncryptMetadataRegistry registry = new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader());
        EncryptColumnRule rule = registry.findByTable("user_phone_encrypt")
                .orElseThrow()
                .findByProperty("phone")
                .orElseThrow();

        assertEquals("id", rule.sourceIdColumn());
        assertEquals("id", rule.sourceIdProperty());
        assertEquals("id", rule.storageIdColumn());
    }

    @Test
    void shouldReturnEmptyForEntityWithoutEncryptAnnotation() {
        EncryptMetadataRegistry registry =
                new EncryptMetadataRegistry(new DatabaseEncryptionProperties(), new AnnotationEncryptMetadataLoader());

        assertTrue(registry.findByEntity(PlainEntity.class).isEmpty());
        assertTrue(registry.findByEntity(PlainEntity.class).isEmpty());
    }

    static class PlainEntity {
        private Long id;
    }
}
