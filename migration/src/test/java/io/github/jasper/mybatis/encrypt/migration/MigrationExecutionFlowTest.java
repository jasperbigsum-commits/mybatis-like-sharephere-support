package io.github.jasper.mybatis.encrypt.migration;

import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.core.metadata.AnnotationEncryptMetadataLoader;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖迁移任务的主执行链路，包含同表模式、独立表模式以及按表名建任务的入口行为。
 */
@DisplayName("迁移执行链路")
class MigrationExecutionFlowTest extends MigrationJdbcTestSupport {

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

    @Test
    void shouldIgnoreStaleCompletedCheckpointWhenSignatureOrDatasourceFingerprintChanges() throws Exception {
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

    @Test
    void shouldRebuildCompletedStateWhenDatabaseDataIsRolledBackToPlaintext() throws Exception {
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
            assertTrue(resultSet.getString("phone_cipher") != null && !resultSet.getString("phone_cipher").isEmpty());
            assertTrue(resultSet.getString("phone_hash") != null && !resultSet.getString("phone_hash").isEmpty());
            assertTrue(resultSet.getString("phone_like") != null && !resultSet.getString("phone_like").isEmpty());
        }
    }
}
