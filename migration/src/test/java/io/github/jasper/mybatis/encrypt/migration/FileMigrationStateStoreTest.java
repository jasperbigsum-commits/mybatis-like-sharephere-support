package io.github.jasper.mybatis.encrypt.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖状态文件的序列化兼容性与错误兜底，避免恢复阶段读到脏数据后静默失败。
 */
@DisplayName("迁移状态存储")
@Tag("unit")
@Tag("migration")
class FileMigrationStateStoreTest extends MigrationJdbcTestSupport {

    /**
     * 测试目的：验证数据迁移任务在同表模式和独立表模式下的完整执行结果。
     * 测试场景：准备源表、独立表和迁移状态目录，执行任务后校验密文数据、辅助列、检查点和报告统计。
     */
    @Test
    void shouldSaveLegacySingleCursorAliasesForBackwardCompatibility() throws Exception {
        Path stateDir = createTempDirectory("migration-state-store");
        FileMigrationStateStore stateStore = new FileMigrationStateStore(stateDir);
        EntityMigrationPlan plan = new EntityMigrationPlan(
                SameTableUserEntity.class,
                SameTableUserEntity.class.getName(),
                "user_account",
                Collections.singletonList("id"),
                100,
                true,
                Collections.emptyList());

        MigrationState state = new MigrationState();
        state.setEntityName(plan.getEntityName());
        state.setTableName(plan.getTableName());
        state.setCursorColumns(Collections.singletonList("id"));
        state.setCursorJavaTypes(Collections.singletonList(Long.class.getName()));
        state.setStatus(MigrationStatus.RUNNING);
        state.setTotalRows(10L);
        state.setRangeStartValues(Collections.singletonList("1"));
        state.setRangeEndValues(Collections.singletonList("10"));
        state.setLastProcessedCursorValues(Collections.singletonList("3"));
        state.setScannedRows(3L);
        state.setMigratedRows(2L);
        state.setSkippedRows(1L);
        state.setVerifiedRows(2L);
        state.setVerificationEnabled(true);

        stateStore.save(plan, state);

        Properties properties = loadSinglePropertiesFile(stateDir);
        assertEquals("id", properties.getProperty("cursorColumn"));
        assertEquals("id", properties.getProperty("idColumn"));
        assertEquals(Long.class.getName(), properties.getProperty("cursorJavaType"));
        assertEquals(Long.class.getName(), properties.getProperty("idJavaType"));
        assertEquals("3", properties.getProperty("lastProcessedCursor"));
        assertEquals("3", properties.getProperty("lastProcessedId"));

        MigrationState loaded = stateStore.load(plan).orElseThrow(AssertionError::new);
        assertEquals(Arrays.asList("id"), loaded.getCursorColumns());
        assertEquals(Arrays.asList(Long.class.getName()), loaded.getCursorJavaTypes());
        assertEquals(Arrays.asList("3"), loaded.getLastProcessedCursorValues());
        assertEquals(MigrationStatus.RUNNING, loaded.getStatus());
    }

    /**
     * 测试目的：验证迁移配置、检查点或数据状态异常时能够安全拒绝执行。
     * 测试场景：构造异常的迁移定义、状态文件或源数据，断言任务快速失败且不会破坏已有迁移进度。
     */
    @Test
    void shouldRejectMalformedStateFile() throws Exception {
        Path stateDir = createTempDirectory("migration-state-store-invalid");
        FileMigrationStateStore stateStore = new FileMigrationStateStore(stateDir);
        EntityMigrationPlan plan = new EntityMigrationPlan(
                SameTableUserEntity.class,
                SameTableUserEntity.class.getName(),
                "user_account",
                Collections.singletonList("id"),
                100,
                true,
                Collections.emptyList());

        Properties malformed = new Properties();
        malformed.setProperty("entityName", plan.getEntityName());
        malformed.setProperty("tableName", plan.getTableName());
        malformed.setProperty("status", "BROKEN");
        malformed.setProperty("totalRows", "abc");
        storeProperties(stateFile(stateDir, plan), malformed, "broken");

        MigrationStateStoreException exception = assertThrows(MigrationStateStoreException.class,
                () -> stateStore.load(plan));
        assertEquals(MigrationErrorCode.STATE_STORE_DATA_INVALID, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("Failed to parse migration state file"));
    }

    /**
     * 测试目的：验证数据迁移任务在同表模式和独立表模式下的完整执行结果。
     * 测试场景：准备源表、独立表和迁移状态目录，执行任务后校验密文数据、辅助列、检查点和报告统计。
     */
    @Test
    void shouldPrefixStateFileWithDatasourceNameWhenPresent() throws Exception {
        Path stateDir = createTempDirectory("migration-state-store-datasource");
        FileMigrationStateStore stateStore = new FileMigrationStateStore(stateDir);
        EntityMigrationPlan plan = new EntityMigrationPlan(
                "archiveDs",
                SameTableUserEntity.class,
                SameTableUserEntity.class.getName(),
                "user_account",
                Collections.singletonList("id"),
                100,
                true,
                Collections.emptyList());

        MigrationState state = new MigrationState();
        state.setDataSourceName("archiveDs");
        state.setEntityName(plan.getEntityName());
        state.setTableName(plan.getTableName());
        state.setCursorColumns(Collections.singletonList("id"));

        stateStore.save(plan, state);

        assertTrue(java.nio.file.Files.exists(stateFile(stateDir, plan)));
        MigrationState loaded = stateStore.load(plan).orElseThrow(AssertionError::new);
        assertEquals("archiveDs", loaded.getDataSourceName());
    }

    /**
     * 测试目的：验证迁移配置、检查点或数据状态异常时能够安全拒绝执行。
     * 测试场景：构造异常的迁移定义、状态文件或源数据，断言任务快速失败且不会破坏已有迁移进度。
     */
    @Test
    void shouldRejectConcurrentCheckpointLockForSamePlan() throws Exception {
        Path stateDir = createTempDirectory("migration-state-store-lock");
        FileMigrationStateStore stateStore = new FileMigrationStateStore(stateDir);
        EntityMigrationPlan plan = new EntityMigrationPlan(
                SameTableUserEntity.class,
                SameTableUserEntity.class.getName(),
                "user_account",
                Collections.singletonList("id"),
                100,
                true,
                Collections.emptyList());

        try (MigrationCheckpointLock ignored = stateStore.acquireCheckpointLock(plan)) {
            MigrationCheckpointLockException exception = assertThrows(MigrationCheckpointLockException.class,
                    () -> stateStore.acquireCheckpointLock(plan));
            assertEquals(MigrationErrorCode.CHECKPOINT_LOCKED, exception.getErrorCode());
        }
    }

    private Path stateFile(Path directory, EntityMigrationPlan plan) {
        StringBuilder fileName = new StringBuilder();
        if (plan.getDataSourceName() != null && !plan.getDataSourceName().trim().isEmpty()) {
            fileName.append(sanitize(plan.getDataSourceName())).append("__");
        }
        fileName.append(sanitize(plan.getEntityName())).append("__").append(sanitize(plan.getTableName())).append(".properties");
        return directory.resolve(fileName.toString());
    }

    private String sanitize(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
