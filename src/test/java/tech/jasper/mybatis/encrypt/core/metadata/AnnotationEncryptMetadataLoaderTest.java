package tech.jasper.mybatis.encrypt.core.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tech.jasper.mybatis.encrypt.annotation.EncryptField;
import tech.jasper.mybatis.encrypt.core.metadata.FieldStorageMode;

class AnnotationEncryptMetadataLoaderTest {

    @Test
    void shouldLoadSeparateTableRuleWithoutEncryptTable() {
        AnnotationEncryptMetadataLoader loader = new AnnotationEncryptMetadataLoader();

        EncryptTableRule tableRule = loader.load(SeparateUserEntity.class);
        EncryptColumnRule rule = tableRule.findByProperty("phone").orElseThrow();

        assertEquals("separate_user_entity", tableRule.getTableName());
        assertTrue(rule.isStoredInSeparateTable());
        assertEquals("user_phone_encrypt", rule.storageTable());
        assertEquals("user_id", rule.storageIdColumn());
        assertEquals("id", rule.sourceIdProperty());
        assertEquals("phone_hash", rule.assistedQueryColumn());
    }

    static class SeparateUserEntity {

        private Long id;

        @EncryptField(
                column = "phone",
                storageMode = FieldStorageMode.SEPARATE_TABLE,
                storageTable = "user_phone_encrypt",
                storageColumn = "phone_cipher",
                sourceIdProperty = "id",
                sourceIdColumn = "id",
                storageIdColumn = "user_id",
                assistedQueryColumn = "phone_hash"
        )
        private String phone;
    }
}
