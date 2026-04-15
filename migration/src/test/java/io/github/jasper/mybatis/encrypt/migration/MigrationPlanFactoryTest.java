package io.github.jasper.mybatis.encrypt.migration;

import io.github.jasper.mybatis.encrypt.migration.plan.EntityMigrationPlanFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖计划构建阶段的内部约束，防止无效选择器或错误元数据在执行期才暴露。
 */
@DisplayName("迁移计划工厂")
class MigrationPlanFactoryTest extends MigrationJdbcTestSupport {

    @Test
    void shouldRejectDtoStyleMultiTableMetadata() {
        MigrationDefinitionException exception = assertThrows(MigrationDefinitionException.class, () ->
                new EntityMigrationPlanFactory(metadataRegistry()).create(
                        EntityMigrationDefinition.builder(MultiTableDto.class, "id").build()));

        assertEquals(MigrationErrorCode.DEFINITION_INVALID, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("ignores DTO fields"));
    }

    @Test
    void shouldRejectUnknownIncludedFieldSelector() {
        MigrationFieldSelectorException exception = assertThrows(MigrationFieldSelectorException.class, () ->
                new EntityMigrationPlanFactory(metadataRegistry()).create(
                        EntityMigrationDefinition.builder(SameTableUserEntity.class, "id")
                                .includeField("unknown_field")
                                .build()));

        assertEquals(MigrationErrorCode.FIELD_SELECTOR_UNRESOLVED, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("Unknown migration field selector"));
    }

    @Test
    void shouldRejectUnknownBackupSelector() {
        MigrationFieldSelectorException exception = assertThrows(MigrationFieldSelectorException.class, () ->
                new EntityMigrationPlanFactory(metadataRegistry()).create(
                        EntityMigrationDefinition.builder(SameTableUserEntity.class, "id")
                                .backupColumn("unknown_field", "phone_backup")
                                .build()));

        assertEquals(MigrationErrorCode.FIELD_SELECTOR_UNRESOLVED, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("Backup column selector"));
    }

    @Test
    void shouldRejectBackupColumnConflictsWithMigrationTargets() {
        MigrationDefinitionException exception = assertThrows(MigrationDefinitionException.class, () ->
                new EntityMigrationPlanFactory(metadataRegistry()).create(
                        EntityMigrationDefinition.builder(SameTableUserEntity.class, "id")
                                .backupColumn("phone", "phone_hash")
                                .build()));

        assertEquals(MigrationErrorCode.BACKUP_COLUMN_CONFLICT, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("Backup column conflicts"));
    }

    @Test
    void shouldRejectMissingRegisteredTableRule() {
        MigrationDefinitionException exception = assertThrows(MigrationDefinitionException.class, () ->
                new EntityMigrationPlanFactory(metadataRegistry()).create(
                        EntityMigrationDefinition.builder("missing_user_account", "id").build()));

        assertEquals(MigrationErrorCode.METADATA_RULE_MISSING, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("Missing registered table encryption rule"));
    }
}
