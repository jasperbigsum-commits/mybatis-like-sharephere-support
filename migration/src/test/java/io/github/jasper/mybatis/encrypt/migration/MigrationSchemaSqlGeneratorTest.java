package io.github.jasper.mybatis.encrypt.migration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("unit")
@Tag("migration")
class MigrationSchemaSqlGeneratorTest extends MigrationJdbcTestSupport {

    @Test
    void shouldGenerateAddColumnSqlUsingSourceLengthHeuristics() throws Exception {
        DataSource dataSource = newDataSource("schema-add-columns");
        executeSql(dataSource,
                "create table user_account (id bigint primary key, phone varchar(64))");

        MigrationSchemaSqlGenerator generator =
                new MigrationSchemaSqlGenerator(dataSource, metadataRegistry(), properties());

        List<String> ddl = generator.generateForEntity(
                SameTableUserEntity.class,
                builder -> builder.backupColumn("phone", "phone_backup"));

        assertEquals(Arrays.asList(
                "alter table `user_account` add column `phone_cipher` varchar(380) after `phone`",
                "alter table `user_account` add column `phone_hash` varchar(64) after `phone_cipher`",
                "alter table `user_account` add column `phone_like` varchar(64) after `phone_hash`"
        ), ddl);
    }

    @Test
    void shouldGenerateModifyColumnSqlWhenExistingSizeIsTooSmall() throws Exception {
        DataSource dataSource = newDataSource("schema-modify-columns");
        executeSql(dataSource,
                "create table user_account ("
                        + "id bigint primary key, "
                        + "phone varchar(64), "
                        + "phone_cipher varchar(100), "
                        + "phone_hash varchar(32), "
                        + "phone_like varchar(16), "
                        + "phone_backup varchar(16))");

        MigrationSchemaSqlGenerator generator =
                new MigrationSchemaSqlGenerator(dataSource, metadataRegistry(), properties());

        List<String> ddl = generator.generateForEntity(
                SameTableUserEntity.class,
                builder -> builder.backupColumn("phone", "phone_backup"));

        assertEquals(Arrays.asList(
                "alter table `user_account` modify column `phone_cipher` varchar(380)",
                "alter table `user_account` modify column `phone_hash` varchar(64)",
                "alter table `user_account` modify column `phone_like` varchar(64)"
        ), ddl);
    }

    @Test
    void shouldGenerateExternalTableSqlForSeparateTableField() throws Exception {
        DataSource dataSource = newDataSource("schema-separate-table");
        executeSql(dataSource,
                "create table user_account (id bigint primary key, id_card varchar(80))",
                "create table user_id_card_encrypt ("
                        + "id varchar(64) primary key, "
                        + "id_card_cipher varchar(64), "
                        + "id_card_hash varchar(32))");

        MigrationSchemaSqlGenerator generator =
                new MigrationSchemaSqlGenerator(dataSource, metadataRegistry(), properties());

        List<String> ddl = generator.generateForEntity(
                SeparateTableUserEntity.class,
                builder -> builder.backupColumn("idCard", "id_card_backup"));

        assertEquals(Arrays.asList(
                "alter table `user_id_card_encrypt` modify column `id_card_cipher` varchar(464)",
                "alter table `user_id_card_encrypt` modify column `id_card_hash` varchar(64)",
                "alter table `user_id_card_encrypt` add column `id_card_like` varchar(80) after `id_card_hash`",
                "alter table `user_account` add column `id_card_backup` varchar(80) after `id_card`"
        ), ddl);
    }

    @Test
    void shouldGenerateCreateTableSqlWhenSeparateTableIsMissing() throws Exception {
        DataSource dataSource = newDataSource("schema-create-separate-table");
        executeSql(dataSource,
                "create table user_account (id bigint primary key, id_card varchar(80))");

        MigrationSchemaSqlGenerator generator =
                new MigrationSchemaSqlGenerator(dataSource, metadataRegistry(), properties());

        List<String> ddl = generator.generateForEntity(SeparateTableUserEntity.class);

        assertEquals(Collections.singletonList(
                "create table `user_id_card_encrypt` (`id` varchar(64) primary key, "
                        + "`id_card_cipher` varchar(464), "
                        + "`id_card_hash` varchar(64), "
                        + "`id_card_like` varchar(80))"
        ), ddl);
    }

    @Test
    void shouldGenerateMaskedColumnSqlForSameTableAndSeparateTable() throws Exception {
        DataSource dataSource = newDataSource("schema-masked-columns");
        executeSql(dataSource,
                "create table user_account ("
                        + "id bigint primary key, "
                        + "phone varchar(64), "
                        + "id_card varchar(80), "
                        + "phone_cipher varchar(380), "
                        + "phone_hash varchar(64), "
                        + "phone_like varchar(64))",
                "create table user_id_card_encrypt ("
                        + "id varchar(64) primary key, "
                        + "id_card_cipher varchar(464), "
                        + "id_card_hash varchar(64), "
                        + "id_card_like varchar(80))");

        MigrationSchemaSqlGenerator generator =
                new MigrationSchemaSqlGenerator(dataSource, metadataRegistry(), properties());

        List<String> sameTableDdl = generator.generateForEntity(MaskedSameTableUserEntity.class);
        List<String> separateTableDdl = generator.generateForEntity(MaskedSeparateTableUserEntity.class);

        assertEquals(Collections.singletonList(
                "alter table `user_account` add column `phone_masked` varchar(64) after `phone_like`"
        ), sameTableDdl);
        assertEquals(Collections.singletonList(
                "alter table `user_id_card_encrypt` add column `id_card_masked` varchar(80) after `id_card_like`"
        ), separateTableDdl);
    }

    @Test
    void shouldGenerateBatchDdlForRegisteredTables() throws Exception {
        DataSource dataSource = newDataSource("schema-batch-ddl");
        executeSql(dataSource,
                "create table user_account (id bigint primary key, phone varchar(64))");
        io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry metadataRegistry = metadataRegistry();
        metadataRegistry.registerEntityType(SameTableUserEntity.class);

        MigrationSchemaSqlGenerator generator =
                new MigrationSchemaSqlGenerator(dataSource, metadataRegistry, properties());

        Map<String, List<String>> ddl = generator.generateAllRegisteredTablesGrouped();

        assertEquals(Collections.singletonMap("user_account", Arrays.asList(
                "alter table `user_account` add column `phone_cipher` varchar(380) after `phone`",
                "alter table `user_account` add column `phone_hash` varchar(64) after `phone_cipher`",
                "alter table `user_account` add column `phone_like` varchar(64) after `phone_hash`"
        )), ddl);
    }

    @Test
    void shouldUseMinimumCipherColumnLengthForShortSourceColumn() throws Exception {
        DataSource dataSource = newDataSource("schema-cipher-min-length");
        executeSql(dataSource,
                "create table user_account (id bigint primary key, phone varchar(1))");

        MigrationSchemaSqlGenerator generator =
                new MigrationSchemaSqlGenerator(dataSource, metadataRegistry(), properties());

        List<String> ddl = generator.generateForEntity(SameTableUserEntity.class);

        assertEquals(Arrays.asList(
                "alter table `user_account` add column `phone_cipher` varchar(64) after `phone`",
                "alter table `user_account` add column `phone_hash` varchar(64) after `phone_cipher`",
                "alter table `user_account` add column `phone_like` varchar(1) after `phone_hash`"
        ), ddl);
    }

    @Test
    void shouldUseLargeTextTypeWhenEstimatedCipherLengthExceedsVarcharLimit() throws Exception {
        DataSource dataSource = newDataSource("schema-cipher-large-text");
        executeSql(dataSource,
                "create table user_account (id bigint primary key, phone varchar(2000))");

        MigrationSchemaSqlGenerator generator =
                new MigrationSchemaSqlGenerator(dataSource, metadataRegistry(), properties());

        List<String> ddl = generator.generateForEntity(SameTableUserEntity.class);

        assertEquals(Arrays.asList(
                "alter table `user_account` add column `phone_cipher` text after `phone`",
                "alter table `user_account` add column `phone_hash` varchar(64) after `phone_cipher`",
                "alter table `user_account` add column `phone_like` varchar(2000) after `phone_hash`"
        ), ddl);
    }

    @Test
    void shouldSkipExcludedTableWhenGeneratingBatchDdl() throws Exception {
        DataSource dataSource = newDataSource("schema-batch-exclude-ddl");
        executeSql(dataSource,
                "create table user_account (id bigint primary key, phone varchar(64))");
        io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry metadataRegistry = metadataRegistry();
        metadataRegistry.registerEntityType(SameTableUserEntity.class);
        io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties properties = properties();
        properties.getMigration().getExcludeTables().add("user_account");

        MigrationSchemaSqlGenerator generator =
                new MigrationSchemaSqlGenerator(dataSource, metadataRegistry, properties);

        assertEquals(Collections.emptyMap(), generator.generateAllRegisteredTablesGrouped());
        assertEquals(Collections.emptyList(), generator.generateAllRegisteredTables());
    }

    @Test
    void shouldRejectAutoCreateTableForClickHouseDialect() throws Exception {
        DataSource dataSource = newDataSource("schema-clickhouse-create");
        executeSql(dataSource,
                "create table user_account (id bigint primary key, id_card varchar(80))");
        io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties properties = properties();
        properties.setSqlDialect(io.github.jasper.mybatis.encrypt.config.SqlDialect.CLICKHOUSE);

        MigrationSchemaSqlGenerator generator =
                new MigrationSchemaSqlGenerator(dataSource, metadataRegistry(), properties);

        MigrationException exception = assertThrows(MigrationException.class,
                () -> generator.generateForEntity(SeparateTableUserEntity.class));

        assertEquals(MigrationErrorCode.DEFINITION_INVALID, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("ClickHouse"));
    }
}
