package io.github.jasper.mybatis.encrypt.migration;

import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.core.metadata.AnnotationEncryptMetadataLoader;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖全局迁移工厂在多数据源场景下的路由行为。
 */
@DisplayName("全局迁移工厂")
@Tag("unit")
@Tag("migration")
class GlobalMigrationTaskFactoryTest extends MigrationJdbcTestSupport {

    /**
     * 测试目的：验证数据迁移任务在同表模式和独立表模式下的完整执行结果。
     * 测试场景：准备源表、独立表和迁移状态目录，执行任务后校验密文数据、辅助列、检查点和报告统计。
     */
    @Test
    void shouldRouteMigrationTaskByDatasourceName() throws Exception {
        DataSource primary = newDataSource("global_primary");
        DataSource archive = newDataSource("global_archive");
        executeSql(primary,
                "create table user_account (" +
                        "id bigint primary key, " +
                        "phone varchar(64), " +
                        "phone_cipher varchar(512), " +
                        "phone_hash varchar(128), " +
                        "phone_like varchar(255))",
                "insert into user_account (id, phone) values (1, '13800138000')");
        executeSql(archive,
                "create table user_account (" +
                        "id bigint primary key, " +
                        "phone varchar(64), " +
                        "phone_cipher varchar(512), " +
                        "phone_hash varchar(128), " +
                        "phone_like varchar(255))",
                "insert into user_account (id, phone) values (1, '13900139000')");

        Map<String, DataSource> dataSources = new LinkedHashMap<>();
        dataSources.put("primaryDs", primary);
        dataSources.put("archiveDs", archive);

        GlobalMigrationTaskFactory factory = new DefaultGlobalMigrationTaskFactory(
                dataSources,
                metadataRegistry(),
                algorithmRegistry(),
                properties(),
                new InMemoryMigrationStateStore(),
                AllowAllMigrationConfirmationPolicy.INSTANCE);

        MigrationReport report = factory.executeForEntity("archiveDs", SameTableUserEntity.class, "id");

        assertEquals("archiveDs", report.getDataSourceName());
        assertEquals(1L, report.getMigratedRows());
        try (Connection connection = archive.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "select phone_cipher, phone_hash, phone_like from user_account where id = 1")) {
            assertTrue(resultSet.next());
            assertTrue(resultSet.getString("phone_cipher") != null && !resultSet.getString("phone_cipher").isEmpty());
            assertTrue(resultSet.getString("phone_hash") != null && !resultSet.getString("phone_hash").isEmpty());
            assertTrue(resultSet.getString("phone_like") != null && !resultSet.getString("phone_like").isEmpty());
        }
        try (Connection connection = primary.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "select phone_cipher from user_account where id = 1")) {
            assertTrue(resultSet.next());
            assertNull(resultSet.getString("phone_cipher"));
        }
    }

    /**
     * 测试目的：验证数据迁移任务在同表模式和独立表模式下的完整执行结果。
     * 测试场景：准备源表、独立表和迁移状态目录，执行任务后校验密文数据、辅助列、检查点和报告统计。
     */
    @Test
    void shouldExecuteAllRegisteredTablesOnlyOncePerPhysicalTable() throws Exception {
        DataSource dataSource = newDataSource("global_all_tables");
        executeSql(dataSource,
                "create table user_account (" +
                        "id bigint primary key, " +
                        "phone varchar(64), " +
                        "phone_cipher varchar(512), " +
                        "phone_hash varchar(128), " +
                        "phone_like varchar(255))",
                "insert into user_account (id, phone) values (1, '13800138000')");

        DatabaseEncryptionProperties properties = configuredProperties();
        EncryptMetadataRegistry registry = new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader());
        registry.registerEntityType(SameTableUserEntity.class);

        Map<String, DataSource> dataSources = new LinkedHashMap<String, DataSource>();
        dataSources.put("primaryDs", dataSource);

        GlobalMigrationTaskFactory factory = new DefaultGlobalMigrationTaskFactory(
                dataSources,
                registry,
                algorithmRegistry(),
                properties,
                new InMemoryMigrationStateStore(),
                AllowAllMigrationConfirmationPolicy.INSTANCE);

        List<MigrationReport> reports = factory.executeAllRegisteredTables("primaryDs");

        assertEquals(1, reports.size());
        assertEquals("user_account", reports.get(0).getTableName());
        assertEquals(1L, reports.get(0).getMigratedRows());
    }

    /**
     * 测试目的：验证覆盖式迁移、备份列和断点续跑的幂等安全行为。
     * 测试场景：准备已迁移或部分迁移的数据状态，执行迁移后校验备份明文、游标检查点和重复执行结果。
     */
    @Test
    void shouldExecuteAllRegisteredTablesRepeatedlyWithoutRemigratingCompletedOverwriteRows() throws Exception {
        DataSource dataSource = newDataSource("global_all_tables_rerun_overwrite");
        executeSql(dataSource,
                "create table user_account (" +
                        "id bigint primary key, " +
                        "phone varchar(128), " +
                        "phone_cipher varchar(512), " +
                        "phone_like varchar(255), " +
                        "phone_backup varchar(128))",
                "insert into user_account (id, phone) values (1, '13800138000')");

        DatabaseEncryptionProperties properties = properties();
        EncryptMetadataRegistry registry = new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader());
        registry.registerEntityType(HashOverwriteUserEntity.class);
        InMemoryMigrationStateStore stateStore = new InMemoryMigrationStateStore();
        Map<String, DataSource> dataSources = new LinkedHashMap<String, DataSource>();
        dataSources.put("primaryDs", dataSource);
        GlobalMigrationTaskFactory factory = new DefaultGlobalMigrationTaskFactory(
                dataSources,
                registry,
                algorithmRegistry(),
                properties,
                stateStore,
                AllowAllMigrationConfirmationPolicy.INSTANCE);

        List<MigrationReport> firstReports = factory.executeAllRegisteredTables(
                "primaryDs",
                builder -> builder.backupColumn("phone", "phone_backup"));
        String firstCipher;
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "select phone_cipher from user_account where id = 1")) {
            assertTrue(resultSet.next());
            firstCipher = resultSet.getString("phone_cipher");
        }

        List<MigrationReport> secondReports = factory.executeAllRegisteredTables(
                "primaryDs",
                builder -> builder.backupColumn("phone", "phone_backup"));

        assertEquals(1, firstReports.size());
        assertEquals(MigrationStatus.COMPLETED, firstReports.get(0).getStatus());
        assertEquals(1L, firstReports.get(0).getMigratedRows());
        assertEquals(1, secondReports.size());
        assertEquals(MigrationStatus.COMPLETED, secondReports.get(0).getStatus());
        assertEquals(1L, secondReports.get(0).getMigratedRows());
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "select phone, phone_cipher, phone_backup from user_account where id = 1")) {
            assertTrue(resultSet.next());
            assertEquals(algorithmRegistry().assisted("sm3").transform("13800138000"),
                    resultSet.getString("phone"));
            assertEquals(firstCipher, resultSet.getString("phone_cipher"));
            assertEquals("13800138000", resultSet.getString("phone_backup"));
        }
    }

    /**
     * 测试目的：验证数据迁移任务在同表模式和独立表模式下的完整执行结果。
     * 测试场景：准备源表、独立表和迁移状态目录，执行任务后校验密文数据、辅助列、检查点和报告统计。
     */
    @Test
    void shouldReuseAllRegisteredTableCheckpointsWhenRollbackKeepsSameCountAndCursorRange() throws Exception {
        DataSource dataSource = newDataSource("global_all_tables_completed_rollback");
        executeSql(dataSource,
                "create table user_account (" +
                        "id bigint primary key, " +
                        "phone varchar(64), " +
                        "phone_cipher varchar(512), " +
                        "phone_hash varchar(128), " +
                        "phone_like varchar(255))",
                "create table user_archive (" +
                        "id bigint primary key, " +
                        "archive_phone varchar(64), " +
                        "archive_phone_cipher varchar(512), " +
                        "archive_phone_hash varchar(128), " +
                        "archive_phone_like varchar(255))",
                "insert into user_account (id, phone) values (1, '13800138000')",
                "insert into user_archive (id, archive_phone) values (1, '13900139000')");

        DatabaseEncryptionProperties properties = properties();
        EncryptMetadataRegistry registry = new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader());
        registry.registerEntityType(SameTableUserEntity.class);
        registry.registerEntityType(ArchiveSameTableUserEntity.class);
        InMemoryMigrationStateStore stateStore = new InMemoryMigrationStateStore();
        Map<String, DataSource> dataSources = new LinkedHashMap<String, DataSource>();
        dataSources.put("primaryDs", dataSource);
        GlobalMigrationTaskFactory factory = new DefaultGlobalMigrationTaskFactory(
                dataSources,
                registry,
                algorithmRegistry(),
                properties,
                stateStore,
                AllowAllMigrationConfirmationPolicy.INSTANCE);

        List<MigrationReport> firstReports = factory.executeAllRegisteredTables("primaryDs");
        executeSql(dataSource,
                "update user_account set phone_cipher = null, phone_hash = null, phone_like = null where id = 1",
                "update user_archive set archive_phone_cipher = null, archive_phone_hash = null, "
                        + "archive_phone_like = null where id = 1");

        List<MigrationReport> secondReports = factory.executeAllRegisteredTables("primaryDs");

        assertEquals(2, firstReports.size());
        assertEquals(2, secondReports.size());
        assertEquals(MigrationStatus.COMPLETED, secondReports.get(0).getStatus());
        assertEquals(MigrationStatus.COMPLETED, secondReports.get(1).getStatus());
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet account = statement.executeQuery(
                     "select phone_cipher, phone_hash, phone_like from user_account where id = 1")) {
            assertTrue(account.next());
            assertNull(account.getString("phone_cipher"));
            assertNull(account.getString("phone_hash"));
            assertNull(account.getString("phone_like"));
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet archive = statement.executeQuery(
                     "select archive_phone_cipher, archive_phone_hash, archive_phone_like from user_archive where id = 1")) {
            assertTrue(archive.next());
            assertNull(archive.getString("archive_phone_cipher"));
            assertNull(archive.getString("archive_phone_hash"));
            assertNull(archive.getString("archive_phone_like"));
        }
    }
}
