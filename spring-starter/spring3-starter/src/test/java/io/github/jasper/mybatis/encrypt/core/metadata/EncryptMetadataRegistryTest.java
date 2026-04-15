package io.github.jasper.mybatis.encrypt.core.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.exception.EncryptionConfigurationException;
import io.github.jasper.mybatis.encrypt.exception.EncryptionErrorCode;
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
        tableRule.getFields().add(fieldRule);
        properties.getTables().add(tableRule);

        EncryptMetadataRegistry registry = new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader());
        EncryptColumnRule rule = registry.findByTable("user_phone_encrypt")
                .orElseThrow()
                .findByColumn("phone_ref")
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
        tableRule.getFields().add(fieldRule);
        properties.getTables().add(tableRule);

        EncryptMetadataRegistry registry = new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader());
        EncryptColumnRule rule = registry.findByTable("user_phone_encrypt")
                .orElseThrow()
                .findByColumn("phone_ref")
                .orElseThrow();

        assertEquals("id", rule.storageIdColumn());
    }

    @Test
    void shouldInferConfiguredPropertyFromColumnAndResolveConfigOnlyEntityRule() {
        DatabaseEncryptionProperties properties = new DatabaseEncryptionProperties();
        DatabaseEncryptionProperties.TableRuleProperties tableRule = new DatabaseEncryptionProperties.TableRuleProperties();
        tableRule.setTable("user_account");

        DatabaseEncryptionProperties.FieldRuleProperties fieldRule = new DatabaseEncryptionProperties.FieldRuleProperties();
        fieldRule.setColumn("id_card");
        fieldRule.setStorageMode(FieldStorageMode.SEPARATE_TABLE);
        fieldRule.setStorageTable("user_id_card_encrypt");
        fieldRule.setStorageColumn("id_card_cipher");
        fieldRule.setAssistedQueryColumn("id_card_hash");
        tableRule.getFields().add(fieldRule);
        properties.getTables().add(tableRule);

        EncryptMetadataRegistry registry = new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader());

        assertTrue(registry.findByTable("user_account")
                .orElseThrow()
                .findByProperty("idCard")
                .isPresent());
        assertTrue(registry.findByEntity(UserAccount.class)
                .orElseThrow()
                .findByProperty("idCard")
                .isPresent());
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

    @Test
    void shouldRejectConfiguredTableRuleWithoutTableName() {
        DatabaseEncryptionProperties properties = new DatabaseEncryptionProperties();
        DatabaseEncryptionProperties.TableRuleProperties tableRule = new DatabaseEncryptionProperties.TableRuleProperties();
        properties.getTables().add(tableRule);

        EncryptionConfigurationException exception = assertThrows(EncryptionConfigurationException.class,
                () -> new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()));

        assertEquals(EncryptionErrorCode.INVALID_TABLE_RULE, exception.getErrorCode());
        assertEquals("Configured table rule must define table name.", exception.getMessage());
    }

    @Test
    void shouldRejectConfiguredSeparateTableFieldWithoutAssistedQueryColumn() {
        DatabaseEncryptionProperties properties = new DatabaseEncryptionProperties();
        DatabaseEncryptionProperties.TableRuleProperties tableRule = new DatabaseEncryptionProperties.TableRuleProperties();
        tableRule.setTable("user_phone_encrypt");

        DatabaseEncryptionProperties.FieldRuleProperties fieldRule = new DatabaseEncryptionProperties.FieldRuleProperties();
        fieldRule.setColumn("phone_ref");
        fieldRule.setStorageMode(FieldStorageMode.SEPARATE_TABLE);
        fieldRule.setStorageTable("user_phone_encrypt_store");
        tableRule.getFields().add(fieldRule);
        properties.getTables().add(tableRule);

        EncryptionConfigurationException exception = assertThrows(EncryptionConfigurationException.class,
                () -> new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()));

        assertEquals(EncryptionErrorCode.MISSING_ASSISTED_QUERY_COLUMN, exception.getErrorCode());
        assertEquals(
                "Separate-table encrypted field must define assistedQueryColumn. property=phoneRef, table=<entity-default-table>, column=phone_ref, storageTable=user_phone_encrypt_store",
                exception.getMessage());
    }

    static class PlainEntity {
        private Long id;
    }

    static class UserAccount {
        private Long id;
        private String idCard;
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
