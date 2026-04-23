package io.github.jasper.mybatis.encrypt.core.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.annotation.TableField;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import io.github.jasper.mybatis.encrypt.annotation.EncryptField;

@Tag("unit")
@Tag("metadata")
class AnnotationEncryptMetadataLoaderTest {

    private final AnnotationEncryptMetadataLoader loader = new AnnotationEncryptMetadataLoader();

    /**
     * 测试目的：验证注解和外部配置能正确合并为表级、字段级加密规则。
     * 测试场景：构造实体注解、第三方列注解和配置属性，断言字段名、来源表、存储列和默认值推断正确。
     */
    @Test
    void shouldResolveColumnFromMybatisPlusBeforeJpa() {
        EncryptTableRule tableRule = loader.load(MixedAnnotationEntity.class);
        EncryptColumnRule rule = tableRule.findByProperty("phone").orElseThrow();

        assertEquals("mixed_annotation_entity", tableRule.getTableName());
        assertEquals("mp_phone", rule.column());
        assertEquals("mp_phone", rule.storageColumn());
    }

    /**
     * 测试目的：验证注解和外部配置能正确合并为表级、字段级加密规则。
     * 测试场景：构造实体注解、第三方列注解和配置属性，断言字段名、来源表、存储列和默认值推断正确。
     */
    @Test
    void shouldResolveColumnFromJpaWhenMybatisPlusAbsent() {
        EncryptTableRule tableRule = loader.load(JpaOnlyEntity.class);
        EncryptColumnRule rule = tableRule.findByProperty("email").orElseThrow();

        assertEquals("jpa_user", tableRule.getTableName());
        assertEquals("email_addr", rule.column());
        assertEquals("email_addr", rule.storageColumn());
    }

    /**
     * 测试目的：验证注解和外部配置能正确合并为表级、字段级加密规则。
     * 测试场景：构造实体注解、第三方列注解和配置属性，断言字段名、来源表、存储列和默认值推断正确。
     */
    @Test
    void shouldFallbackToSnakeCaseWhenNoThirdPartyColumnAnnotationExists() {
        EncryptTableRule tableRule = loader.load(DefaultNamingEntity.class);
        EncryptColumnRule rule = tableRule.findByProperty("mobilePhone").orElseThrow();

        assertEquals("default_naming_entity", tableRule.getTableName());
        assertEquals("mobile_phone", rule.column());
        assertEquals("mobile_phone", rule.storageColumn());
    }

    /**
     * 测试目的：验证注解和外部配置能正确合并为表级、字段级加密规则。
     * 测试场景：构造实体注解、第三方列注解和配置属性，断言字段名、来源表、存储列和默认值推断正确。
     */
    @Test
    void shouldDefaultSeparateTableStorageIdColumnToIdWhenNotConfigured() {
        EncryptTableRule tableRule = loader.load(MybatisPlusSeparateUserEntity.class);
        EncryptColumnRule rule = tableRule.findByProperty("phone").orElseThrow();

        assertTrue(rule.isStoredInSeparateTable());
        assertEquals("id", rule.storageIdColumn());
    }

    /**
     * 测试目的：验证注解和外部配置能正确合并为表级、字段级加密规则。
     * 测试场景：构造实体注解、第三方列注解和配置属性，断言字段名、来源表、存储列和默认值推断正确。
     */
    @Test
    void shouldKeepExplicitSeparateTableStorageIdColumn() {
        EncryptTableRule tableRule = loader.load(SeparateUserEntity.class);
        EncryptColumnRule rule = tableRule.findByProperty("phone").orElseThrow();

        assertTrue(rule.isStoredInSeparateTable());
        assertEquals("user_id", rule.storageIdColumn());
    }

    /**
     * 测试目的：验证注解和外部配置能正确合并为表级、字段级加密规则。
     * 测试场景：构造实体注解、第三方列注解和配置属性，断言字段名、来源表、存储列和默认值推断正确。
     */
    @Test
    void shouldDefaultSeparateTableStorageIdColumnToIdForJpaEntity() {
        EncryptTableRule tableRule = loader.load(JpaSeparateUserEntity.class);
        EncryptColumnRule rule = tableRule.findByProperty("phone").orElseThrow();

        assertTrue(rule.isStoredInSeparateTable());
        assertEquals("id", rule.storageIdColumn());
    }

    /**
     * 测试目的：验证注解和外部配置能正确合并为表级、字段级加密规则。
     * 测试场景：构造实体注解、第三方列注解和配置属性，断言字段名、来源表、存储列和默认值推断正确。
     */
    @Test
    void shouldDefaultSeparateTableStorageIdColumnToIdWithoutIdMetadata() {
        EncryptTableRule tableRule = loader.load(SnakeCaseSeparateUserEntity.class);
        EncryptColumnRule rule = tableRule.findByProperty("phone").orElseThrow();

        assertTrue(rule.isStoredInSeparateTable());
        assertEquals("id", rule.storageIdColumn());
    }

    /**
     * 测试目的：验证注解和外部配置能正确合并为表级、字段级加密规则。
     * 测试场景：构造实体注解、第三方列注解和配置属性，断言字段名、来源表、存储列和默认值推断正确。
     */
    @Test
    void shouldKeepFieldLevelSourceTableForDtoField() {
        EncryptTableRule tableRule = loader.load(MultiTableDto.class);
        EncryptColumnRule phoneRule = tableRule.findByProperty("phone").orElseThrow();
        EncryptColumnRule archivePhoneRule = tableRule.findByProperty("archivePhone").orElseThrow();

        assertEquals("user_account", phoneRule.table());
        assertEquals("user_archive", archivePhoneRule.table());
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
                storageIdColumn = "user_id",
                assistedQueryColumn = "phone_hash"
        )
        private String phone;
    }

    static class MybatisPlusSeparateUserEntity {

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
                assistedQueryColumn = "phone_hash"
        )
        private String phone;
    }

    static class MultiTableDto {

        @EncryptField(
                table = "user_account",
                column = "phone",
                storageColumn = "phone_cipher"
        )
        private String phone;

        @EncryptField(
                table = "user_archive",
                column = "archive_phone",
                storageColumn = "archive_phone_cipher"
        )
        private String archivePhone;
    }
}
