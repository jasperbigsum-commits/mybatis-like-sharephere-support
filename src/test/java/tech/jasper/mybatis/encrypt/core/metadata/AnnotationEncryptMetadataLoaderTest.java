package tech.jasper.mybatis.encrypt.core.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;
import tech.jasper.mybatis.encrypt.annotation.EncryptField;

class AnnotationEncryptMetadataLoaderTest {

    private final AnnotationEncryptMetadataLoader loader = new AnnotationEncryptMetadataLoader();

    @Test
    void shouldResolveColumnFromMybatisPlusBeforeJpa() {
        EncryptTableRule tableRule = loader.load(MixedAnnotationEntity.class);
        EncryptColumnRule rule = tableRule.findByProperty("phone").orElseThrow();

        assertEquals("mixed_annotation_entity", tableRule.getTableName());
        assertEquals("mp_phone", rule.column());
        assertEquals("mp_phone", rule.storageColumn());
    }

    @Test
    void shouldResolveColumnFromJpaWhenMybatisPlusAbsent() {
        EncryptTableRule tableRule = loader.load(JpaOnlyEntity.class);
        EncryptColumnRule rule = tableRule.findByProperty("email").orElseThrow();

        assertEquals("jpa_user", tableRule.getTableName());
        assertEquals("email_addr", rule.column());
        assertEquals("email_addr", rule.storageColumn());
    }

    @Test
    void shouldFallbackToSnakeCaseWhenNoThirdPartyColumnAnnotationExists() {
        EncryptTableRule tableRule = loader.load(DefaultNamingEntity.class);
        EncryptColumnRule rule = tableRule.findByProperty("mobilePhone").orElseThrow();

        assertEquals("default_naming_entity", tableRule.getTableName());
        assertEquals("mobile_phone", rule.column());
        assertEquals("mobile_phone", rule.storageColumn());
    }

    @Test
    void shouldInferSourceIdPropertyFromMybatisPlusIdColumn() {
        EncryptTableRule tableRule = loader.load(MybatisPlusSeparateUserEntity.class);
        EncryptColumnRule rule = tableRule.findByProperty("phone").orElseThrow();

        assertTrue(rule.isStoredInSeparateTable());
        assertEquals("user_id", rule.sourceIdColumn());
        assertEquals("bizId", rule.sourceIdProperty());
        assertEquals("user_id", rule.storageIdColumn());
    }

    @Test
    void shouldInferSourceIdPropertyFromJpaIdColumn() {
        EncryptTableRule tableRule = loader.load(JpaSeparateUserEntity.class);
        EncryptColumnRule rule = tableRule.findByProperty("phone").orElseThrow();

        assertTrue(rule.isStoredInSeparateTable());
        assertEquals("tenant_user_id", rule.sourceIdColumn());
        assertEquals("tenantUserId", rule.sourceIdProperty());
        assertEquals("tenant_user_id", rule.storageIdColumn());
    }

    @Test
    void shouldInferSourceIdPropertyFromSnakeCaseColumnWhenNoIdAnnotationExists() {
        EncryptTableRule tableRule = loader.load(SnakeCaseSeparateUserEntity.class);
        EncryptColumnRule rule = tableRule.findByProperty("phone").orElseThrow();

        assertTrue(rule.isStoredInSeparateTable());
        assertEquals("order_id", rule.sourceIdColumn());
        assertEquals("orderId", rule.sourceIdProperty());
        assertEquals("order_id", rule.storageIdColumn());
    }

    static class MixedAnnotationEntity {

        @EncryptField
        @TableField("mp_phone")
        @Column(name = "jpa_phone")
        private String phone;
    }

    @Table(name = "jpa_user")
    static class JpaOnlyEntity {

        @EncryptField
        @Column(name = "email_addr")
        private String email;
    }

    static class DefaultNamingEntity {

        @EncryptField
        private String mobilePhone;
    }

    static class SeparateUserEntity {

        private Long id;

        @EncryptField(
                column = "phone",
                storageMode = FieldStorageMode.SEPARATE_TABLE,
                storageTable = "user_phone_encrypt",
                storageColumn = "phone_cipher",
                sourceIdColumn = "id",
                storageIdColumn = "user_id",
                assistedQueryColumn = "phone_hash"
        )
        private String phone;
    }

    static class MybatisPlusSeparateUserEntity {

        @TableId("user_id")
        private Long bizId;

        @EncryptField(
                column = "phone",
                storageMode = FieldStorageMode.SEPARATE_TABLE,
                storageTable = "user_phone_encrypt",
                assistedQueryColumn = "phone_hash"
        )
        private String phone;
    }

    static class JpaSeparateUserEntity {

        @Id
        @Column(name = "tenant_user_id")
        private Long tenantUserId;

        @EncryptField(
                column = "phone",
                storageMode = FieldStorageMode.SEPARATE_TABLE,
                storageTable = "user_phone_encrypt",
                assistedQueryColumn = "phone_hash"
        )
        private String phone;
    }

    static class SnakeCaseSeparateUserEntity {

        private Long orderId;

        @EncryptField(
                column = "phone",
                storageMode = FieldStorageMode.SEPARATE_TABLE,
                storageTable = "user_phone_encrypt",
                sourceIdColumn = "order_id",
                assistedQueryColumn = "phone_hash"
        )
        private String phone;
    }
}
