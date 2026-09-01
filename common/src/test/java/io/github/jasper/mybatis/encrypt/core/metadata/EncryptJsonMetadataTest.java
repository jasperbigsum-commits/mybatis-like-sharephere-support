package io.github.jasper.mybatis.encrypt.core.metadata;

import io.github.jasper.mybatis.encrypt.annotation.EncryptJsonField;
import io.github.jasper.mybatis.encrypt.annotation.EncryptJsonPath;
import io.github.jasper.mybatis.encrypt.annotation.EncryptTable;
import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.exception.EncryptionConfigurationException;
import io.github.jasper.mybatis.encrypt.exception.EncryptionErrorCode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
@Tag("metadata")
class EncryptJsonMetadataTest {

    @Test
    void shouldLoadEncryptJsonFieldWithMultiplePathsFromAnnotation() {
        EncryptMetadataRegistry registry =
                new EncryptMetadataRegistry(new DatabaseEncryptionProperties(), new AnnotationEncryptMetadataLoader());

        EncryptTableRule tableRule = registry.findByEntity(AnnotatedJsonUserEntity.class).orElseThrow(AssertionError::new);
        EncryptJsonFieldRule jsonFieldRule = tableRule.findJsonFieldByProperty("profileJson")
                .orElseThrow(AssertionError::new);

        assertEquals("user_account", tableRule.getTableName());
        assertEquals("user_account", jsonFieldRule.table());
        assertEquals("profile_json", jsonFieldRule.column());
        assertEquals("sm4", jsonFieldRule.cipherAlgorithm());
        assertEquals("sm3", jsonFieldRule.assistedQueryAlgorithm());
        assertEquals(2, jsonFieldRule.pathRules().size());
        assertEquals("$.phone", jsonFieldRule.pathRules().get(0).path());
        assertEquals("phone_encrypt", jsonFieldRule.pathRules().get(0).storageTable());
        assertEquals("phone_hash", jsonFieldRule.pathRules().get(0).hashColumn());
        assertEquals("phone_cipher", jsonFieldRule.pathRules().get(0).cipherColumn());
        assertEquals("sm4-custom", jsonFieldRule.pathRules().get(1).cipherAlgorithm());
    }

    @Test
    void shouldLoadEncryptJsonFieldFromConfiguredProperties() {
        DatabaseEncryptionProperties properties = new DatabaseEncryptionProperties();
        DatabaseEncryptionProperties.TableRuleProperties tableRule =
                new DatabaseEncryptionProperties.TableRuleProperties();
        tableRule.setTable("user_account");

        DatabaseEncryptionProperties.JsonFieldRuleProperties jsonFieldRule =
                new DatabaseEncryptionProperties.JsonFieldRuleProperties();
        jsonFieldRule.setProperty("profileJson");
        jsonFieldRule.setColumn("profile_json");
        jsonFieldRule.setCipherAlgorithm("sm4");
        jsonFieldRule.setAssistedQueryAlgorithm("sm3");

        DatabaseEncryptionProperties.JsonPathRuleProperties phonePath =
                new DatabaseEncryptionProperties.JsonPathRuleProperties();
        phonePath.setPath("$.phone");
        phonePath.setStorageTable("phone_encrypt");
        phonePath.setStorageIdColumn("id");
        phonePath.setHashColumn("phone_hash");
        phonePath.setCipherColumn("phone_cipher");
        jsonFieldRule.getPaths().add(phonePath);

        tableRule.getJsonFields().add(jsonFieldRule);
        properties.getTables().add(tableRule);

        EncryptMetadataRegistry registry =
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader());

        EncryptJsonFieldRule resolved = registry.findByTable("user_account")
                .orElseThrow(AssertionError::new)
                .findJsonFieldByProperty("profileJson")
                .orElseThrow(AssertionError::new);

        assertEquals("profile_json", resolved.column());
        assertEquals(1, resolved.pathRules().size());
        assertEquals("$.phone", resolved.pathRules().get(0).path());
        assertEquals("phone_encrypt", resolved.pathRules().get(0).storageTable());
    }

    @Test
    void shouldRejectEncryptJsonFieldOnNonStringProperty() {
        EncryptMetadataRegistry registry =
                new EncryptMetadataRegistry(new DatabaseEncryptionProperties(), new AnnotationEncryptMetadataLoader());

        EncryptionConfigurationException exception = assertThrows(
                EncryptionConfigurationException.class,
                () -> registry.findByEntity(InvalidJsonTypeEntity.class)
        );

        assertEquals(EncryptionErrorCode.INVALID_FIELD_RULE, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("@EncryptJsonField only supports String properties"));
    }

    @Test
    void shouldRejectNonExactJsonPath() {
        EncryptMetadataRegistry registry =
                new EncryptMetadataRegistry(new DatabaseEncryptionProperties(), new AnnotationEncryptMetadataLoader());

        EncryptionConfigurationException exception = assertThrows(
                EncryptionConfigurationException.class,
                () -> registry.findByEntity(InvalidJsonPathEntity.class)
        );

        assertEquals(EncryptionErrorCode.INVALID_FIELD_RULE, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("must use exact json path"));
    }

    @Test
    void shouldRejectJsonPathWithoutSeparateTableBinding() {
        EncryptMetadataRegistry registry =
                new EncryptMetadataRegistry(new DatabaseEncryptionProperties(), new AnnotationEncryptMetadataLoader());

        EncryptionConfigurationException exception = assertThrows(
                EncryptionConfigurationException.class,
                () -> registry.findByEntity(MissingJsonStorageBindingEntity.class)
        );

        assertEquals(EncryptionErrorCode.MISSING_STORAGE_TABLE, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("EncryptJsonPath must define storageTable"));
    }

    @EncryptTable("user_account")
    static class AnnotatedJsonUserEntity {

        @EncryptJsonField(
                column = "profile_json",
                cipherAlgorithm = "sm4",
                assistedQueryAlgorithm = "sm3",
                paths = {
                        @EncryptJsonPath(
                                path = "$.phone",
                                storageTable = "phone_encrypt",
                                storageIdColumn = "id",
                                hashColumn = "phone_hash",
                                cipherColumn = "phone_cipher"
                        ),
                        @EncryptJsonPath(
                                path = "$.idCard",
                                storageTable = "id_card_encrypt",
                                storageIdColumn = "id",
                                hashColumn = "id_card_hash",
                                cipherColumn = "id_card_cipher",
                                cipherAlgorithm = "sm4-custom"
                        )
                }
        )
        private String profileJson;
    }

    @EncryptTable("user_account")
    static class InvalidJsonTypeEntity {

        @EncryptJsonField(
                column = "profile_json",
                paths = {
                        @EncryptJsonPath(
                                path = "$.phone",
                                storageTable = "phone_encrypt",
                                hashColumn = "phone_hash",
                                cipherColumn = "phone_cipher"
                        )
                }
        )
        private Long profileJson;
    }

    @EncryptTable("user_account")
    static class InvalidJsonPathEntity {

        @EncryptJsonField(
                column = "profile_json",
                paths = {
                        @EncryptJsonPath(
                                path = "$..phone",
                                storageTable = "phone_encrypt",
                                hashColumn = "phone_hash",
                                cipherColumn = "phone_cipher"
                        )
                }
        )
        private String profileJson;
    }

    @EncryptTable("user_account")
    static class MissingJsonStorageBindingEntity {

        @EncryptJsonField(
                column = "profile_json",
                paths = {
                        @EncryptJsonPath(
                                path = "$.phone",
                                hashColumn = "phone_hash",
                                cipherColumn = "phone_cipher"
                        )
                }
        )
        private String profileJson;
    }
}
