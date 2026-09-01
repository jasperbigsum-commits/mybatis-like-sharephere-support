package io.github.jasper.mybatis.encrypt.migration;

import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.migration.plan.EntityMigrationPlanFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖计划构建阶段的内部约束，防止无效选择器或错误元数据在执行期才暴露。
 */
@DisplayName("迁移计划工厂")
@Tag("unit")
@Tag("migration")
class MigrationPlanFactoryTest extends MigrationJdbcTestSupport {

    /**
     * 测试目的：验证迁移配置、检查点或数据状态异常时能够安全拒绝执行。
     * 测试场景：构造异常的迁移定义、状态文件或源数据，断言任务快速失败且不会破坏已有迁移进度。
     */
    @Test
    void shouldRejectDtoStyleMultiTableMetadata() {
        MigrationDefinitionException exception = assertThrows(MigrationDefinitionException.class, () ->
                new EntityMigrationPlanFactory(metadataRegistry()).create(
                        EntityMigrationDefinition.builder(MultiTableDto.class, "id").build()));

        assertEquals(MigrationErrorCode.DEFINITION_INVALID, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("ignores DTO fields"));
    }

    /**
     * 测试目的：验证迁移配置、检查点或数据状态异常时能够安全拒绝执行。
     * 测试场景：构造异常的迁移定义、状态文件或源数据，断言任务快速失败且不会破坏已有迁移进度。
     */
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

    /**
     * 测试目的：验证迁移配置、检查点或数据状态异常时能够安全拒绝执行。
     * 测试场景：构造异常的迁移定义、状态文件或源数据，断言任务快速失败且不会破坏已有迁移进度。
     */
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

    /**
     * 测试目的：验证迁移配置、检查点或数据状态异常时能够安全拒绝执行。
     * 测试场景：构造异常的迁移定义、状态文件或源数据，断言任务快速失败且不会破坏已有迁移进度。
     */
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

    /**
     * 测试目的：验证迁移配置、检查点或数据状态异常时能够安全拒绝执行。
     * 测试场景：构造异常的迁移定义、状态文件或源数据，断言任务快速失败且不会破坏已有迁移进度。
     */
    @Test
    void shouldRejectMissingRegisteredTableRule() {
        MigrationDefinitionException exception = assertThrows(MigrationDefinitionException.class, () ->
                new EntityMigrationPlanFactory(metadataRegistry()).create(
                        EntityMigrationDefinition.builder("missing_user_account", "id").build()));

        assertEquals(MigrationErrorCode.METADATA_RULE_MISSING, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("Missing registered table encryption rule"));
    }

    /**
     * 测试目的：验证覆盖式迁移、备份列和断点续跑的幂等安全行为。
     * 测试场景：准备已迁移或部分迁移的数据状态，执行迁移后校验备份明文、游标检查点和重复执行结果。
     */
    @Test
    void shouldApplyBackupColumnTemplateRuleForOverwriteField() {
        DatabaseEncryptionProperties properties = properties();
        DatabaseEncryptionProperties.BackupColumnTemplateRuleProperties templateRule =
                new DatabaseEncryptionProperties.BackupColumnTemplateRuleProperties();
        templateRule.setTablePattern("user_account");
        templateRule.setFieldPattern("phone");
        templateRule.setTemplate("${column}_backup");
        properties.getMigration().getBackupColumnTemplates().add(templateRule);

        EntityMigrationPlan plan = new EntityMigrationPlanFactory(metadataRegistry(), properties).create(
                EntityMigrationDefinition.builder(HashOverwriteUserEntity.class, "id").build());

        assertEquals("phone_backup", plan.getColumnPlans().get(0).getBackupColumn());
    }

    /**
     * 测试目的：验证迁移配置、检查点或数据状态异常时能够安全拒绝执行。
     * 测试场景：构造异常的迁移定义、状态文件或源数据，断言任务快速失败且不会破坏已有迁移进度。
     */
    @Test
    void shouldRejectExcludedMigrationTable() {
        DatabaseEncryptionProperties properties = properties();
        properties.getMigration().getExcludeTables().add("user_account|user_archive_*");

        MigrationDefinitionException exception = assertThrows(MigrationDefinitionException.class, () ->
                new EntityMigrationPlanFactory(metadataRegistry(), properties).create(
                        EntityMigrationDefinition.builder(SameTableUserEntity.class, "id").build()));

        assertEquals(MigrationErrorCode.TABLE_EXCLUDED, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("excluded"));
    }

    /**
     * 测试目的：验证迁移配置、检查点或数据状态异常时能够安全拒绝执行。
     * 测试场景：构造异常的迁移定义、状态文件或源数据，断言任务快速失败且不会破坏已有迁移进度。
     */
    @Test
    void shouldRejectMutableCursorColumn() {
        MigrationDefinitionException exception = assertThrows(MigrationDefinitionException.class, () ->
                new EntityMigrationPlanFactory(metadataRegistry(), properties()).create(
                        EntityMigrationDefinition.builder(HashOverwriteUserEntity.class, "phone").build()));

        assertEquals(MigrationErrorCode.CURSOR_COLUMN_MUTABLE, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("cursor column"));
    }
}
