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
}
