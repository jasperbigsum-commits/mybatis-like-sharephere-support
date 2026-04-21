package io.github.jasper.mybatis.encrypt.migration;

import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.core.metadata.AnnotationEncryptMetadataLoader;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import org.junit.jupiter.api.DisplayName;
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
class GlobalMigrationTaskFactoryTest extends MigrationJdbcTestSupport {

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

        Map<String, DataSource> dataSources = new LinkedHashMap<String, DataSource>();
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
}
