package io.github.jasper.mybatis.encrypt.core.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import org.junit.jupiter.api.Test;

class EncryptMetadataRegistryTest {

    @Test
    void shouldUseConfiguredSeparateTableStorageIdColumn() {
        DatabaseEncryptionProperties properties = new DatabaseEncryptionProperties();
        DatabaseEncryptionProperties.TableRuleProperties tableRule = new DatabaseEncryptionProperties.TableRuleProperties();
        tableRule.setTable("user_phone_encrypt");

        DatabaseEncryptionProperties.FieldRuleProperties fieldRule = new DatabaseEncryptionProperties.FieldRuleProperties();
        fieldRule.setColumn("phone_ref");
        fieldRule.setStorageMode(FieldStorageMode.SEPARATE_TABLE);
        fieldRule.setStorageTable("user_phone_encrypt_store");
        fieldRule.setStorageIdColumn("encrypt_id");
        fieldRule.setAssistedQueryColumn("phone_hash");
        tableRule.getFields().put("phone", fieldRule);
        properties.getTables().put("userPhoneEncrypt", tableRule);

        EncryptMetadataRegistry registry = new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader());
        EncryptColumnRule rule = registry.findByTable("user_phone_encrypt")
                .orElseThrow()
                .findByProperty("phone")
                .orElseThrow();

        assertEquals("encrypt_id", rule.storageIdColumn());
    }

    @Test
    void shouldDefaultSeparateTableStorageIdColumnToIdWithoutBusinessSourceLink() {
        DatabaseEncryptionProperties properties = new DatabaseEncryptionProperties();
        DatabaseEncryptionProperties.TableRuleProperties tableRule = new DatabaseEncryptionProperties.TableRuleProperties();
        tableRule.setTable("user_phone_encrypt");

        DatabaseEncryptionProperties.FieldRuleProperties fieldRule = new DatabaseEncryptionProperties.FieldRuleProperties();
        fieldRule.setColumn("phone_ref");
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

        assertEquals("id", rule.storageIdColumn());
    }

    @Test
    void shouldReturnEmptyForEntityWithoutEncryptAnnotation() {
        EncryptMetadataRegistry registry =
                new EncryptMetadataRegistry(new DatabaseEncryptionProperties(), new AnnotationEncryptMetadataLoader());

        assertTrue(registry.findByEntity(PlainEntity.class).isEmpty());
    }

    @Test
    void shouldRegisterFieldLevelRulesToDifferentSourceTables() {
        EncryptMetadataRegistry registry =
                new EncryptMetadataRegistry(new DatabaseEncryptionProperties(), new AnnotationEncryptMetadataLoader());

        EncryptTableRule entityRule = registry.findByEntity(MultiTableDto.class).orElseThrow();

        assertEquals("phone", entityRule.findByProperty("phone").orElseThrow().column());
        assertEquals("archive_phone", entityRule.findByProperty("archivePhone").orElseThrow().column());
        assertTrue(registry.findByTable("user_account")
                .orElseThrow()
                .findByProperty("phone")
                .isPresent());
        assertTrue(registry.findByTable("user_archive")
                .orElseThrow()
                .findByProperty("archivePhone")
                .isPresent());
    }

    static class PlainEntity {
        private Long id;
    }

    static class MultiTableDto {

        @io.github.jasper.mybatis.encrypt.annotation.EncryptField(
                table = "user_account",
                column = "phone",
                storageColumn = "phone_cipher"
        )
        private String phone;

        @io.github.jasper.mybatis.encrypt.annotation.EncryptField(
                table = "user_archive",
                column = "archive_phone",
                storageColumn = "archive_phone_cipher"
        )
        private String archivePhone;
    }
}
