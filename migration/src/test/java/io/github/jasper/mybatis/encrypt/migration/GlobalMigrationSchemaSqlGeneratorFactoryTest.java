package io.github.jasper.mybatis.encrypt.migration;

import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.config.SqlDialect;
import io.github.jasper.mybatis.encrypt.core.metadata.AnnotationEncryptMetadataLoader;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import io.github.jasper.mybatis.encrypt.core.metadata.FieldStorageMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("全局 DDL 生成工厂")
@Tag("unit")
@Tag("migration")
class GlobalMigrationSchemaSqlGeneratorFactoryTest extends MigrationJdbcTestSupport {

    /**
     * 测试目的：验证迁移 DDL 生成逻辑能按字段规则补齐密文列、脱敏列或独立表结构。
     * 测试场景：构造不同方言、字段长度和表结构状态，断言生成的建表/改表 SQL 符合迁移预期。
     */
    @Test
    void shouldRouteBatchDdlGenerationByDatasourceNameAndDialect() throws Exception {
        DataSource primary = newDataSource("global_schema_primary");
        DataSource archive = newDataSource("global_schema_archive");
        executeSql(primary,
                "create table user_account (id bigint primary key, phone varchar(64))");
        executeSql(archive,
                "create table user_account (id bigint primary key, phone varchar(64))");

        DatabaseEncryptionProperties properties = configuredProperties();
        DatabaseEncryptionProperties.DataSourceDialectRuleProperties archiveDialect =
                new DatabaseEncryptionProperties.DataSourceDialectRuleProperties();
        archiveDialect.setDatasourceNamePattern("archiveDs");
        archiveDialect.setSqlDialect(SqlDialect.ORACLE12);
        properties.getDatasourceDialects().add(archiveDialect);

        EncryptMetadataRegistry registry = new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader());
        registry.registerEntityType(SameTableUserEntity.class);

        Map<String, DataSource> dataSources = new LinkedHashMap<>();
        dataSources.put("primaryDs", primary);
        dataSources.put("archiveDs", archive);

        GlobalMigrationSchemaSqlGeneratorFactory factory =
                new DefaultGlobalMigrationSchemaSqlGeneratorFactory(dataSources, registry, properties);

        List<String> primaryDdl = factory.generateAllRegisteredTables("primaryDs");
        List<String> archiveDdl = factory.generateAllRegisteredTables("archiveDs");

        assertEquals(Arrays.asList(
                "alter table `user_account` add column `phone_cipher` varchar(380) after `phone`",
                "alter table `user_account` add column `phone_hash` varchar(64) after `phone_cipher`",
                "alter table `user_account` add column `phone_like` varchar(64) after `phone_hash`"
        ), primaryDdl);
        assertEquals(Arrays.asList(
                "alter table \"user_account\" add (\"phone_cipher\" varchar2(380))",
                "alter table \"user_account\" add (\"phone_hash\" varchar2(64))",
                "alter table \"user_account\" add (\"phone_like\" varchar2(64))"
        ), archiveDdl);
    }

    /**
     * 测试目的：验证迁移 DDL 生成逻辑能按字段规则补齐密文列、脱敏列或独立表结构。
     * 测试场景：构造不同方言、字段长度和表结构状态，断言生成的建表/改表 SQL 符合迁移预期。
     */
    @Test
    void shouldGenerateMaskedColumnDdlFromConfiguredRules() throws Exception {
        DataSource primary = newDataSource("global_schema_masked_primary");
        executeSql(primary,
                "create table user_account (id bigint primary key, phone varchar(64))");

        DatabaseEncryptionProperties properties = configuredMaskedProperties();
        EncryptMetadataRegistry registry = new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader());

        Map<String, DataSource> dataSources = new LinkedHashMap<>();
        dataSources.put("primaryDs", primary);

        GlobalMigrationSchemaSqlGeneratorFactory factory =
                new DefaultGlobalMigrationSchemaSqlGeneratorFactory(dataSources, registry, properties);

        List<String> ddl = factory.generateAllRegisteredTables("primaryDs");

        assertEquals(Arrays.asList(
                "alter table `user_account` add column `phone_cipher` varchar(380) after `phone`",
                "alter table `user_account` add column `phone_hash` varchar(64) after `phone_cipher`",
                "alter table `user_account` add column `phone_like` varchar(64) after `phone_hash`",
                "alter table `user_account` add column `phone_masked` varchar(64) after `phone_like`"
        ), ddl);
    }

    /**
     * 测试目的：验证全量 DDL 生成工厂能识别字段级 backupColumn 配置，并在独立表覆盖主列时同步校验主表源列长度。
     * 测试场景：使用纯配置注册身份证独立表字段，主表原列长度只有 18 且配置 id_card_backup；只调用 generateAllRegisteredTables，断言会先扩容主表原列，再导出备份列和独立表建表 SQL。
     */
    @Test
    void shouldGenerateSeparateTableBackupColumnFromConfiguredFieldRule() throws Exception {
        DataSource primary = newDataSource("global_schema_separate_backup_primary");
        executeSql(primary,
                "create table user_account (id bigint primary key, id_card varchar(18))");

        DatabaseEncryptionProperties properties = properties();
        DatabaseEncryptionProperties.TableRuleProperties tableRule =
                new DatabaseEncryptionProperties.TableRuleProperties();
        tableRule.setTable("user_account");
        DatabaseEncryptionProperties.FieldRuleProperties idCardRule =
                new DatabaseEncryptionProperties.FieldRuleProperties();
        idCardRule.setProperty("idCard");
        idCardRule.setColumn("id_card");
        idCardRule.setStorageMode(FieldStorageMode.SEPARATE_TABLE);
        idCardRule.setStorageTable("user_id_card_encrypt");
        idCardRule.setStorageColumn("id_card_cipher");
        idCardRule.setStorageIdColumn("id");
        idCardRule.setAssistedQueryColumn("id_card_hash");
        idCardRule.setLikeQueryColumn("id_card_like");
        idCardRule.setBackupColumn("id_card_backup");
        tableRule.getFields().add(idCardRule);
        properties.getTables().add(tableRule);

        EncryptMetadataRegistry registry = new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader());
        Map<String, DataSource> dataSources = new LinkedHashMap<>();
        dataSources.put("primaryDs", primary);
        GlobalMigrationSchemaSqlGeneratorFactory factory =
                new DefaultGlobalMigrationSchemaSqlGeneratorFactory(dataSources, registry, properties);

        List<String> ddl = factory.generateAllRegisteredTables("primaryDs");

        assertEquals(Arrays.asList(
                "alter table `user_account` modify column `id_card` varchar(64)",
                "alter table `user_account` add column `id_card_backup` varchar(18) after `id_card`",
                "create table `user_id_card_encrypt` (`id` varchar(64) primary key, "
                        + "`id_card_cipher` varchar(136), "
                        + "`id_card_hash` varchar(64), "
                        + "`id_card_like` varchar(18), "
                        + "`id_card_backup` varchar(18))"
        ), ddl);
    }
}
