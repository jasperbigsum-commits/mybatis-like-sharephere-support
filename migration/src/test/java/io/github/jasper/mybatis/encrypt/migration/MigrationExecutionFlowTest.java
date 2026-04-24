package io.github.jasper.mybatis.encrypt.migration;

import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.core.metadata.AnnotationEncryptMetadataLoader;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import io.github.jasper.mybatis.encrypt.migration.jdbc.JdbcEntityMigrationTask;
import io.github.jasper.mybatis.encrypt.migration.jdbc.JdbcMigrationRecordReader;
import io.github.jasper.mybatis.encrypt.migration.jdbc.JdbcMigrationRecordVerifier;
import io.github.jasper.mybatis.encrypt.migration.jdbc.JdbcMigrationRecordWriter;
import io.github.jasper.mybatis.encrypt.migration.plan.EntityMigrationPlanFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖迁移任务的主执行链路，包含同表模式、独立表模式以及按表名建任务的入口行为。
 */
@DisplayName("迁移执行链路")
@Tag("unit")
@Tag("migration")
class MigrationExecutionFlowTest extends MigrationJdbcTestSupport {

    /**
     * 测试目的：验证数据迁移任务在同表模式和独立表模式下的完整执行结果。
     * 测试场景：准备源表、独立表和迁移状态目录，执行任务后校验密文数据、辅助列、检查点和报告统计。
     */
    @Test
    void shouldMigrateSameTableColumnsAndPersistProgressFile() throws Exception {
        DataSource dataSource = newDataSource("same_table");
        executeSql(dataSource,
                "create table user_account (" +
                        "id bigint primary key, " +
                        "phone varchar(64), " +
                        "phone_cipher varchar(512), " +
                        "phone_hash varchar(128), " +
                        "phone_like varchar(255))",
                "insert into user_account (id, phone) values (1, '13800138000')",
                "insert into user_account (id, phone) values (2, '13900139000')");

        Path stateDir = createTempDirectory("migration-state-same");
        MigrationTask task = JdbcMigrationTasks.create(
                dataSource,
                EntityMigrationDefinition.builder(SameTableUserEntity.class, "id").batchSize(1).build(),
                metadataRegistry(),
                algorithmRegistry(),
                properties(),
                new FileMigrationStateStore(stateDir)
        );

        MigrationReport report = task.execute();

        assertEquals(MigrationStatus.COMPLETED, report.getStatus());
        assertEquals(2L, report.getTotalRows());
        assertEquals(2L, report.getScannedRows());
        assertEquals(2L, report.getMigratedRows());
        assertEquals(2L, report.getVerifiedRows());
        assertEquals(0L, report.getSkippedRows());
        assertEquals("2", report.getLastProcessedCursor());

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "select phone, phone_cipher, phone_hash, phone_like from user_account where id = 1")) {
            assertTrue(resultSet.next());
            assertEquals("13800138000", resultSet.getString("phone"));
            assertTrue(resultSet.getString("phone_cipher") != null && !resultSet.getString("phone_cipher").isEmpty());
            assertTrue(resultSet.getString("phone_hash") != null && !resultSet.getString("phone_hash").isEmpty());
            assertTrue(resultSet.getString("phone_like") != null && !resultSet.getString("phone_like").isEmpty());
        }

        Properties state = loadSinglePropertiesFile(stateDir);
        assertEquals("COMPLETED", state.getProperty("status"));
        assertEquals("2", state.getProperty("totalRows"));
        assertEquals("1", state.getProperty("rangeStart"));
        assertEquals("2", state.getProperty("rangeEnd"));
        assertEquals("2", state.getProperty("lastProcessedId"));
    }

    /**
     * 测试目的：验证数据迁移任务在同表模式和独立表模式下的完整执行结果。
     * 测试场景：准备源表、独立表和迁移状态目录，执行任务后校验密文数据、辅助列、检查点和报告统计。
     */
    @Test
    void shouldMigrateSeparateTableColumnsAndReplaceMainColumnWithReferenceHash() throws Exception {
        DataSource dataSource = newDataSource("separate_table");
        executeSql(dataSource,
                "create table user_account (" +
                        "id bigint primary key, " +
                        "id_card varchar(64))",
                "create table user_id_card_encrypt (" +
                        "id varchar(64) primary key, " +
                        "id_card_cipher varchar(512), " +
                        "id_card_hash varchar(128), " +
                        "id_card_like varchar(255))",
                "insert into user_account (id, id_card) values (1, '320101199001011234')",
                "insert into user_account (id, id_card) values (2, '320101199001011235')");

        MigrationTask task = JdbcMigrationTasks.create(
                dataSource,
                EntityMigrationDefinition.builder(SeparateTableUserEntity.class, "id").build(),
                metadataRegistry(),
                algorithmRegistry(),
                properties(),
                new FileMigrationStateStore(createTempDirectory("migration-state-separate"))
        );

        MigrationReport report = task.execute();

        assertEquals(MigrationStatus.COMPLETED, report.getStatus());
        assertEquals(2L, report.getMigratedRows());
        assertEquals(2L, report.getVerifiedRows());

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet mainResult = statement.executeQuery("select id_card from user_account where id = 1")) {
            assertTrue(mainResult.next());
            String migratedReferenceId = mainResult.getString(1);
            assertNotEquals("320101199001011234", migratedReferenceId);
            try (ResultSet externalResult = statement.executeQuery(
                    "select id_card_cipher, id_card_hash, id_card_like from user_id_card_encrypt where id_card_hash = '"
                            + migratedReferenceId + "'")) {
                assertTrue(externalResult.next());
                assertNotNull(externalResult.getString("id_card_cipher"));
                assertEquals(migratedReferenceId, externalResult.getString("id_card_hash"));
                assertNotNull(externalResult.getString("id_card_like"));
            }
        }
    }

    /**
     * 测试目的：验证独立表写入失败时整批事务会回滚，不会让主表留下半迁移状态。
     * 测试场景：故意把外表密文字段长度设得过短，执行迁移后校验主表明文与备份列、外表行数都保持未提交状态。
     */
    @Test
    void shouldRollbackSeparateTableBatchWhenExternalCipherColumnIsTooShort() throws Exception {
        DataSource dataSource = newDataSource("separate_table_insert_failure_rollback");
        executeSql(dataSource,
                "create table user_account (" +
                        "id bigint primary key, " +
                        "id_card varchar(64), " +
                        "id_card_backup varchar(64))",
                "create table user_id_card_encrypt (" +
                        "id varchar(64) primary key, " +
                        "id_card_cipher varchar(8), " +
                        "id_card_hash varchar(128), " +
                        "id_card_like varchar(255))",
                "insert into user_account (id, id_card) values (1, '320101199001011234')");

        MigrationTask task = JdbcMigrationTasks.create(
                dataSource,
                EntityMigrationDefinition.builder(SeparateTableUserEntity.class, "id")
                        .backupColumnByColumn("id_card", "id_card_backup")
                        .build(),
                metadataRegistry(),
                algorithmRegistry(),
                properties(),
                new FileMigrationStateStore(createTempDirectory("migration-state-separate-rollback"))
        );

        MigrationExecutionException exception = assertThrows(MigrationExecutionException.class, task::execute);

        assertEquals(MigrationErrorCode.EXECUTION_FAILED, exception.getErrorCode());
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet mainResult = statement.executeQuery(
                     "select id_card, id_card_backup from user_account where id = 1")) {
            assertTrue(mainResult.next());
            assertEquals("320101199001011234", mainResult.getString("id_card"));
            assertNull(mainResult.getString("id_card_backup"));
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet externalCount = statement.executeQuery("select count(1) from user_id_card_encrypt")) {
            assertTrue(externalCount.next());
            assertEquals(0, externalCount.getInt(1));
        }
    }

    /**
     * 测试目的：验证数据迁移任务在同表模式和独立表模式下的完整执行结果。
     * 测试场景：准备源表、独立表和迁移状态目录，执行任务后校验密文数据、辅助列、检查点和报告统计。
     */
    @Test
    void shouldPersistStoredMaskedColumnsDuringMigration() throws Exception {
        DataSource dataSource = newDataSource("masked_columns_migration");
        executeSql(dataSource,
                "create table user_account ("
                        + "id bigint primary key, "
                        + "phone varchar(64), "
                        + "phone_cipher varchar(512), "
                        + "phone_hash varchar(128), "
                        + "phone_like varchar(255), "
                        + "phone_masked varchar(255), "
                        + "id_card varchar(64))",
                "create table user_id_card_encrypt ("
                        + "id varchar(64) primary key, "
                        + "id_card_cipher varchar(512), "
                        + "id_card_hash varchar(128), "
                        + "id_card_like varchar(255), "
                        + "id_card_masked varchar(255))",
                "insert into user_account (id, phone, id_card) values (1, '13800138000', '320101199001011234')");

        MigrationTask sameTableTask = JdbcMigrationTasks.create(
                dataSource,
                EntityMigrationDefinition.builder(MaskedSameTableUserEntity.class, "id").build(),
                metadataRegistry(),
                algorithmRegistry(),
                properties(),
                new FileMigrationStateStore(createTempDirectory("migration-state-masked-same"))
        );
        MigrationTask separateTableTask = JdbcMigrationTasks.create(
                dataSource,
                EntityMigrationDefinition.builder(MaskedSeparateTableUserEntity.class, "id").build(),
                metadataRegistry(),
                algorithmRegistry(),
                properties(),
                new FileMigrationStateStore(createTempDirectory("migration-state-masked-separate"))
        );

        assertEquals(MigrationStatus.COMPLETED, sameTableTask.execute().getStatus());
        assertEquals(MigrationStatus.COMPLETED, separateTableTask.execute().getStatus());

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet sameTableResult = statement.executeQuery(
                     "select phone_masked from user_account where id = 1")) {
            assertTrue(sameTableResult.next());
            assertEquals(algorithmRegistry().like("phoneMaskLike").transform("13800138000"),
                    sameTableResult.getString("phone_masked"));
        }

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet separateTableResult = statement.executeQuery(
                     "select id_card_masked from user_id_card_encrypt")) {
            assertTrue(separateTableResult.next());
            assertEquals(algorithmRegistry().like("idCardMaskLike").transform("320101199001011234"),
                    separateTableResult.getString("id_card_masked"));
        }
    }

    /**
     * 测试目的：验证数据迁移任务在同表模式和独立表模式下的完整执行结果。
     * 测试场景：准备源表、独立表和迁移状态目录，执行任务后校验密文数据、辅助列、检查点和报告统计。
     */
    @Test
    void shouldReuseLikeColumnWhenMaskedColumnSharesSamePhysicalColumnDuringMigration() throws Exception {
        DataSource dataSource = newDataSource("shared_like_masked_columns_migration");
        executeSql(dataSource,
                "create table user_account ("
                        + "id bigint primary key, "
                        + "phone varchar(64), "
                        + "phone_cipher varchar(512), "
                        + "phone_hash varchar(128), "
                        + "phone_like varchar(255), "
                        + "id_card varchar(64))",
                "create table user_id_card_encrypt ("
                        + "id varchar(64) primary key, "
                        + "id_card_cipher varchar(512), "
                        + "id_card_hash varchar(128), "
                        + "id_card_like varchar(255))",
                "insert into user_account (id, phone, id_card) values (1, '13800138000', '320101199001011234')");

        MigrationTask sameTableTask = JdbcMigrationTasks.create(
                dataSource,
                EntityMigrationDefinition.builder(SharedLikeMaskedSameTableUserEntity.class, "id").build(),
                metadataRegistry(),
                algorithmRegistry(),
                properties(),
                new FileMigrationStateStore(createTempDirectory("migration-state-shared-masked-same"))
        );
        MigrationTask separateTableTask = JdbcMigrationTasks.create(
                dataSource,
                EntityMigrationDefinition.builder(SharedLikeMaskedSeparateTableUserEntity.class, "id").build(),
                metadataRegistry(),
                algorithmRegistry(),
                properties(),
                new FileMigrationStateStore(createTempDirectory("migration-state-shared-masked-separate"))
        );

        assertEquals(MigrationStatus.COMPLETED, sameTableTask.execute().getStatus());
        assertEquals(MigrationStatus.COMPLETED, separateTableTask.execute().getStatus());

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet sameTableResult = statement.executeQuery(
                     "select phone_like from user_account where id = 1")) {
            assertTrue(sameTableResult.next());
            assertEquals(algorithmRegistry().like("phoneMaskLike").transform("13800138000"),
                    sameTableResult.getString("phone_like"));
        }

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet separateTableResult = statement.executeQuery(
                     "select id_card_like from user_id_card_encrypt")) {
            assertTrue(separateTableResult.next());
            assertEquals(algorithmRegistry().like("idCardMaskLike").transform("320101199001011234"),
                    separateTableResult.getString("id_card_like"));
        }
    }

    /**
     * 测试目的：验证数据迁移任务在同表模式和独立表模式下的完整执行结果。
     * 测试场景：准备源表、独立表和迁移状态目录，执行任务后校验密文数据、辅助列、检查点和报告统计。
     */
    @Test
    void shouldMigrateConfiguredTableRuleByTableName() throws Exception {
        DataSource dataSource = newDataSource("config_table_name");
        executeSql(dataSource,
                "create table user_account (" +
                        "id bigint primary key, " +
                        "phone varchar(64), " +
                        "phone_cipher varchar(512), " +
                        "phone_hash varchar(128), " +
                        "phone_like varchar(255))",
                "insert into user_account (id, phone) values (1, '13800138000')");

        DatabaseEncryptionProperties properties = configuredProperties();
        MigrationTask task = JdbcMigrationTasks.create(
                dataSource,
                EntityMigrationDefinition.builder("user_account", "id").build(),
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                algorithmRegistry(),
                properties,
                new FileMigrationStateStore(createTempDirectory("migration-state-config-table"))
        );

        MigrationReport report = task.execute();

        assertEquals(MigrationStatus.COMPLETED, report.getStatus());
        assertEquals("user_account", report.getEntityName());
        assertEquals(1L, report.getMigratedRows());
        assertEquals(1L, report.getVerifiedRows());
    }

    /**
     * 测试目的：验证数据迁移任务在同表模式和独立表模式下的完整执行结果。
     * 测试场景：准备源表、独立表和迁移状态目录，执行任务后校验密文数据、辅助列、检查点和报告统计。
     */
    @Test
    void shouldMigrateUsingNonIdCursorColumnName() throws Exception {
        DataSource dataSource = newDataSource("non_id_cursor");
        executeSql(dataSource,
                "create table user_account (" +
                        "record_no bigint primary key, " +
                        "phone varchar(64), " +
                        "phone_cipher varchar(512), " +
                        "phone_hash varchar(128), " +
                        "phone_like varchar(255))",
                "insert into user_account (record_no, phone) values (10, '13800138000')",
                "insert into user_account (record_no, phone) values (11, '13900139000')");

        DatabaseEncryptionProperties properties = configuredProperties();
        MigrationTask task = JdbcMigrationTasks.create(
                dataSource,
                EntityMigrationDefinition.builder("user_account", "record_no").batchSize(1).build(),
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                algorithmRegistry(),
                properties,
                new FileMigrationStateStore(createTempDirectory("migration-state-non-id-cursor"))
        );

        MigrationReport report = task.execute();

        assertEquals(MigrationStatus.COMPLETED, report.getStatus());
        assertEquals("11", report.getLastProcessedCursor());
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "select phone_cipher, phone_hash, phone_like from user_account where record_no = 10")) {
            assertTrue(resultSet.next());
            assertTrue(resultSet.getString("phone_cipher") != null && !resultSet.getString("phone_cipher").isEmpty());
            assertTrue(resultSet.getString("phone_hash") != null && !resultSet.getString("phone_hash").isEmpty());
            assertTrue(resultSet.getString("phone_like") != null && !resultSet.getString("phone_like").isEmpty());
        }
    }

    /**
     * 测试目的：验证数据迁移任务在同表模式和独立表模式下的完整执行结果。
     * 测试场景：准备源表、独立表和迁移状态目录，执行任务后校验密文数据、辅助列、检查点和报告统计。
     */
    @Test
    void shouldMigrateAnnotatedTableRuleByTableNameAfterRegistryWarmUp() throws Exception {
        DataSource dataSource = newDataSource("annotation_table_name");
        executeSql(dataSource,
                "create table user_account (" +
                        "id bigint primary key, " +
                        "phone varchar(64), " +
                        "phone_cipher varchar(512), " +
                        "phone_hash varchar(128), " +
                        "phone_like varchar(255))",
                "insert into user_account (id, phone) values (1, '13800138000')");

        DatabaseEncryptionProperties properties = properties();
        EncryptMetadataRegistry metadataRegistry = new EncryptMetadataRegistry(
                properties, new AnnotationEncryptMetadataLoader());
        metadataRegistry.registerEntityType(SameTableUserEntity.class);

        MigrationTask task = JdbcMigrationTasks.create(
                dataSource,
                EntityMigrationDefinition.builder("user_account", "id").build(),
                metadataRegistry,
                algorithmRegistry(),
                properties,
                new FileMigrationStateStore(createTempDirectory("migration-state-annotation-table"))
        );

        MigrationReport report = task.execute();

        assertEquals(MigrationStatus.COMPLETED, report.getStatus());
        assertEquals("user_account", report.getEntityName());
        assertEquals(1L, report.getMigratedRows());
        assertEquals(1L, report.getVerifiedRows());
    }

    /**
     * 测试目的：验证数据迁移任务在同表模式和独立表模式下的完整执行结果。
     * 测试场景：准备源表、独立表和迁移状态目录，执行任务后校验密文数据、辅助列、检查点和报告统计。
     */
    @Test
    void shouldUseDefaultCursorColumnsForFactoryShortcutMethods() throws Exception {
        DataSource dataSource = newDataSource("default_cursor_shortcut");
        executeSql(dataSource,
                "create table user_account (" +
                        "id bigint primary key, " +
                        "phone varchar(64), " +
                        "phone_cipher varchar(512), " +
                        "phone_hash varchar(128), " +
                        "phone_like varchar(255))",
                "insert into user_account (id, phone) values (1, '13800138000')");

        DefaultMigrationTaskFactory taskFactory = new DefaultMigrationTaskFactory(
                dataSource,
                metadataRegistry(),
                algorithmRegistry(),
                properties(),
                new FileMigrationStateStore(createTempDirectory("migration-state-default-cursor")),
                AllowAllMigrationConfirmationPolicy.INSTANCE);

        MigrationReport report = taskFactory.executeForEntity(SameTableUserEntity.class);

        assertEquals(MigrationStatus.COMPLETED, report.getStatus());
        assertEquals(1L, report.getMigratedRows());
        assertEquals("1", report.getLastProcessedCursor());
    }

    /**
     * 测试目的：验证数据迁移任务在同表模式和独立表模式下的完整执行结果。
     * 测试场景：准备源表、独立表和迁移状态目录，执行任务后校验密文数据、辅助列、检查点和报告统计。
     */
    @Test
    void shouldMigrateUsingTimestampCursorColumn() throws Exception {
        DataSource dataSource = newDataSource("timestamp_cursor");
        executeSql(dataSource,
                "create table user_account (" +
                        "id bigint primary key, " +
                        "created_at timestamp not null, " +
                        "phone varchar(64), " +
                        "phone_cipher varchar(512), " +
                        "phone_hash varchar(128), " +
                        "phone_like varchar(255))",
                "insert into user_account (id, created_at, phone) values (1, TIMESTAMP '2026-04-17 09:00:00', '13800138000')",
                "insert into user_account (id, created_at, phone) values (2, TIMESTAMP '2026-04-17 09:00:01', '13900139000')");

        DatabaseEncryptionProperties properties = configuredProperties();
        MigrationTask task = JdbcMigrationTasks.create(
                dataSource,
                EntityMigrationDefinition.builder("user_account", "created_at").batchSize(1).build(),
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                algorithmRegistry(),
                properties,
                new FileMigrationStateStore(createTempDirectory("migration-state-timestamp-cursor"))
        );

        MigrationReport report = task.execute();

        assertEquals(MigrationStatus.COMPLETED, report.getStatus());
        assertEquals(2L, report.getMigratedRows());
        assertEquals(2L, report.getVerifiedRows());
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "select phone_cipher, phone_hash, phone_like from user_account where id = 1")) {
            assertTrue(resultSet.next());
            assertTrue(resultSet.getString("phone_cipher") != null && !resultSet.getString("phone_cipher").isEmpty());
            assertTrue(resultSet.getString("phone_hash") != null && !resultSet.getString("phone_hash").isEmpty());
            assertTrue(resultSet.getString("phone_like") != null && !resultSet.getString("phone_like").isEmpty());
        }
    }

    /**
     * 测试目的：验证数据迁移任务在同表模式和独立表模式下的完整执行结果。
     * 测试场景：准备源表、独立表和迁移状态目录，执行任务后校验密文数据、辅助列、检查点和报告统计。
     */
    @Test
    void shouldUseTableSpecificDefaultCursorColumns() throws Exception {
        DataSource dataSource = newDataSource("table_specific_cursor");
        executeSql(dataSource,
                "create table user_account (" +
                        "record_no bigint primary key, " +
                        "phone varchar(64), " +
                        "phone_cipher varchar(512), " +
                        "phone_hash varchar(128), " +
                        "phone_like varchar(255))",
                "insert into user_account (record_no, phone) values (10, '13800138000')",
                "insert into user_account (record_no, phone) values (11, '13900139000')");

        DatabaseEncryptionProperties properties = configuredProperties();
        DatabaseEncryptionProperties.TableCursorRuleProperties cursorRule =
                new DatabaseEncryptionProperties.TableCursorRuleProperties();
        cursorRule.setTablePattern("user_account");
        cursorRule.setCursorColumns(java.util.Collections.singletonList("record_no"));
        properties.getMigration().getCursorRules().add(cursorRule);

        DefaultMigrationTaskFactory taskFactory = new DefaultMigrationTaskFactory(
                dataSource,
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                algorithmRegistry(),
                properties,
                new FileMigrationStateStore(createTempDirectory("migration-state-table-specific-cursor")),
                AllowAllMigrationConfirmationPolicy.INSTANCE);

        MigrationReport report = taskFactory.executeForTable("user_account");

        assertEquals(MigrationStatus.COMPLETED, report.getStatus());
        assertEquals(2L, report.getMigratedRows());
        assertEquals("11", report.getLastProcessedCursor());
    }

    /**
     * 测试目的：验证迁移配置、检查点或数据状态异常时能够安全拒绝执行。
     * 测试场景：构造异常的迁移定义、状态文件或源数据，断言任务快速失败且不会破坏已有迁移进度。
     */
    @Test
    void shouldRejectIncompatibleCheckpointWithoutOverwritingState() throws Exception {
        DataSource dataSource = newDataSource("stale_checkpoint");
        executeSql(dataSource,
                "create table user_account (" +
                        "id bigint primary key, " +
                        "phone varchar(64), " +
                        "phone_cipher varchar(512), " +
                        "phone_hash varchar(128), " +
                        "phone_like varchar(255))",
                "insert into user_account (id, phone) values (1, '13800138000')",
                "insert into user_account (id, phone) values (2, '13900139000')");

        Path stateDir = createTempDirectory("migration-state-stale-checkpoint");
        FileMigrationStateStore stateStore = new FileMigrationStateStore(stateDir);
        EntityMigrationPlan plan = new io.github.jasper.mybatis.encrypt.migration.plan.EntityMigrationPlanFactory(metadataRegistry(), properties())
                .create(EntityMigrationDefinition.builder(SameTableUserEntity.class, "id").build());
        MigrationState staleState = new MigrationState();
        staleState.setDataSourceName(plan.getDataSourceName());
        staleState.setDataSourceFingerprint("legacy-db");
        staleState.setPlanSignature("legacy-plan");
        staleState.setEntityName(plan.getEntityName());
        staleState.setTableName(plan.getTableName());
        staleState.setCursorColumns(plan.getCursorColumns());
        staleState.setStatus(MigrationStatus.COMPLETED);
        staleState.setTotalRows(2L);
        staleState.setRangeStartValues(java.util.Collections.singletonList("1"));
        staleState.setRangeEndValues(java.util.Collections.singletonList("2"));
        staleState.setLastProcessedCursorValues(java.util.Collections.singletonList("2"));
        staleState.setScannedRows(99L);
        staleState.setMigratedRows(99L);
        staleState.setVerifiedRows(99L);
        stateStore.save(plan, staleState);

        MigrationTask task = JdbcMigrationTasks.create(
                dataSource,
                EntityMigrationDefinition.builder(SameTableUserEntity.class, "id").build(),
                metadataRegistry(),
                algorithmRegistry(),
                properties(),
                stateStore
        );
        MigrationExecutionException exception = assertThrows(MigrationExecutionException.class, task::execute);

        assertEquals(MigrationErrorCode.STATE_INCOMPATIBLE, exception.getErrorCode());
        Properties persistedState = loadSinglePropertiesFile(stateDir);
        assertEquals("legacy-db", persistedState.getProperty("dataSourceFingerprint"));
        assertEquals("legacy-plan", persistedState.getProperty("planSignature"));
        assertEquals("COMPLETED", persistedState.getProperty("status"));
        assertEquals("99", persistedState.getProperty("scannedRows"));
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(
                     "select phone_cipher, phone_hash, phone_like from user_account where id = 1")) {
            assertTrue(resultSet.next());
            assertNull(resultSet.getString("phone_cipher"));
            assertNull(resultSet.getString("phone_hash"));
            assertNull(resultSet.getString("phone_like"));
        }
    }

    /**
     * 测试目的：验证数据迁移任务在同表模式和独立表模式下的完整执行结果。
     * 测试场景：准备源表、独立表和迁移状态目录，执行任务后校验密文数据、辅助列、检查点和报告统计。
     */
    @Test
    void shouldReuseCompletedCheckpointWithoutReadingBatchesAgainWhenSnapshotIsUnchanged() throws Exception {
        DataSource dataSource = newDataSource("completed_state_fast_reuse");
        executeSql(dataSource,
                "create table user_account (" +
                        "id bigint primary key, " +
                        "phone varchar(64), " +
                        "phone_cipher varchar(512), " +
                        "phone_hash varchar(128), " +
                        "phone_like varchar(255))",
                "insert into user_account (id, phone) values (1, '13800138000')");

        Path stateDir = createTempDirectory("migration-state-completed-fast-reuse");
        FileMigrationStateStore stateStore = new FileMigrationStateStore(stateDir);
        DatabaseEncryptionProperties properties = properties();
        EncryptMetadataRegistry metadataRegistry = new EncryptMetadataRegistry(
                properties, new AnnotationEncryptMetadataLoader());
        EntityMigrationPlan plan = new EntityMigrationPlanFactory(metadataRegistry, properties)
                .create(EntityMigrationDefinition.builder(SameTableUserEntity.class, "id").build());

        MigrationTask firstTask = JdbcMigrationTasks.create(
                dataSource,
                EntityMigrationDefinition.builder(SameTableUserEntity.class, "id").build(),
                metadataRegistry,
                algorithmRegistry(),
                properties,
                stateStore
        );
        assertEquals(MigrationStatus.COMPLETED, firstTask.execute().getStatus());

        CountingMigrationRecordReader countingReader = new CountingMigrationRecordReader(properties);
        MigrationTask rerunTask = new JdbcEntityMigrationTask(
                dataSource,
                plan,
                countingReader,
                countingReader,
                new JdbcMigrationRecordWriter(properties, algorithmRegistry(), new SnowflakeReferenceIdGenerator()),
                new JdbcMigrationRecordVerifier(properties, algorithmRegistry()),
                stateStore,
                AllowAllMigrationConfirmationPolicy.INSTANCE
        );

        MigrationReport rerunReport = rerunTask.execute();

        assertEquals(MigrationStatus.COMPLETED, rerunReport.getStatus());
        assertEquals(0, countingReader.batchReadCount());
    }

    /**
     * 测试目的：验证数据迁移任务在同表模式和独立表模式下的完整执行结果。
     * 测试场景：准备源表、独立表和迁移状态目录，执行任务后校验密文数据、辅助列、检查点和报告统计。
     */
    @Test
    void shouldTrustCompletedCheckpointWhenRollbackKeepsSameCountAndCursorRange() throws Exception {
        DataSource dataSource = newDataSource("completed_state_plaintext_rollback");
        executeSql(dataSource,
                "create table user_account (" +
                        "id bigint primary key, " +
                        "phone varchar(64), " +
                        "phone_cipher varchar(512), " +
                        "phone_hash varchar(128), " +
                        "phone_like varchar(255))",
                "insert into user_account (id, phone) values (1, '13800138000')");

        Path stateDir = createTempDirectory("migration-state-completed-recheck");
        FileMigrationStateStore stateStore = new FileMigrationStateStore(stateDir);
        MigrationTask firstTask = JdbcMigrationTasks.create(
                dataSource,
                EntityMigrationDefinition.builder(SameTableUserEntity.class, "id").build(),
                metadataRegistry(),
                algorithmRegistry(),
                properties(),
                stateStore
        );
        MigrationReport firstReport = firstTask.execute();
        assertEquals(1L, firstReport.getMigratedRows());

        executeSql(dataSource,
                "update user_account set phone_cipher = null, phone_hash = null, phone_like = null where id = 1");

        MigrationTask rerunTask = JdbcMigrationTasks.create(
                dataSource,
                EntityMigrationDefinition.builder(SameTableUserEntity.class, "id").build(),
                metadataRegistry(),
                algorithmRegistry(),
                properties(),
                stateStore
        );
        MigrationReport rerunReport = rerunTask.execute();

        assertEquals(MigrationStatus.COMPLETED, rerunReport.getStatus());
        assertEquals(1L, rerunReport.getMigratedRows());
        assertEquals(1L, rerunReport.getVerifiedRows());
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "select phone, phone_cipher, phone_hash, phone_like from user_account where id = 1")) {
            assertTrue(resultSet.next());
            assertEquals("13800138000", resultSet.getString("phone"));
            assertNull(resultSet.getString("phone_cipher"));
            assertNull(resultSet.getString("phone_hash"));
            assertNull(resultSet.getString("phone_like"));
        }
    }

    private static final class CountingMigrationRecordReader extends JdbcMigrationRecordReader {

        private final AtomicInteger batchReadCount = new AtomicInteger();

        private CountingMigrationRecordReader(DatabaseEncryptionProperties properties) {
            super(properties);
        }

        @Override
        public List<MigrationRecord> readBatch(Connection connection,
                                               EntityMigrationPlan plan,
                                               MigrationCursor lastProcessedCursor) throws SQLException {
            batchReadCount.incrementAndGet();
            return super.readBatch(connection, plan, lastProcessedCursor);
        }

        private int batchReadCount() {
            return batchReadCount.get();
        }
    }
}
