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

    /**
     * 测试目的：验证迁移 DDL 生成逻辑能按字段规则补齐密文列、脱敏列或独立表结构。
     * 测试场景：构造不同方言、字段长度和表结构状态，断言生成的建表/改表 SQL 符合迁移预期。
     */
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

    /**
     * 测试目的：验证迁移 DDL 生成逻辑能按字段规则补齐密文列、脱敏列或独立表结构。
     * 测试场景：构造不同方言、字段长度和表结构状态，断言生成的建表/改表 SQL 符合迁移预期。
     */
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

    /**
     * 测试目的：验证迁移 DDL 生成逻辑能按字段规则补齐密文列、脱敏列或独立表结构。
     * 测试场景：构造不同方言、字段长度和表结构状态，断言生成的建表/改表 SQL 符合迁移预期。
     */
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
                "alter table `user_account` add column `id_card_backup` varchar(80) after `id_card`",
                "alter table `user_id_card_encrypt` modify column `id_card_cipher` varchar(464)",
                "alter table `user_id_card_encrypt` modify column `id_card_hash` varchar(64)",
                "alter table `user_id_card_encrypt` add column `id_card_like` varchar(80) after `id_card_hash`",
                "alter table `user_id_card_encrypt` add column `id_card_backup` varchar(80) after `id_card_like`"
        ), ddl);
    }

    /**
     * 测试目的：验证迁移配置、检查点或数据状态异常时能够安全拒绝执行。
     * 测试场景：构造异常的迁移定义、状态文件或源数据，断言任务快速失败且不会破坏已有迁移进度。
     */
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

    /**
     * 测试目的：验证独立表缺失时导出的迁移 DDL 仍会同步补齐主表明文备份列。
     * 测试场景：构造只存在主表、独立表尚未创建的迁移前状态，配置备份列后断言导出 SQL 先补主表备份列，再创建独立表结构。
     */
    @Test
    void shouldGenerateMainTableBackupColumnBeforeMissingSeparateTableDdl() throws Exception {
        DataSource dataSource = newDataSource("schema-create-separate-table-with-backup");
        executeSql(dataSource,
                "create table user_account (id bigint primary key, id_card varchar(80))");

        MigrationSchemaSqlGenerator generator =
                new MigrationSchemaSqlGenerator(dataSource, metadataRegistry(), properties());

        List<String> ddl = generator.generateForEntity(
                SeparateTableUserEntity.class,
                builder -> builder.backupColumn("idCard", "id_card_backup"));

        assertEquals(Arrays.asList(
                "alter table `user_account` add column `id_card_backup` varchar(80) after `id_card`",
                "create table `user_id_card_encrypt` (`id` varchar(64) primary key, "
                        + "`id_card_cipher` varchar(464), "
                        + "`id_card_hash` varchar(64), "
                        + "`id_card_like` varchar(80), "
                        + "`id_card_backup` varchar(80))"
        ), ddl);
    }

    /**
     * 测试目的：验证独立表模式下主表备份列如果已经存在但长度偏小，单表导出也会补齐 modify DDL。
     * 测试场景：模拟身份证原列长度为 80，但历史上误建了 16 长度的备份列，断言生成器会先扩容备份列，再补齐独立表结构。
     */
    @Test
    void shouldModifySeparateTableBackupColumnWhenExistingLengthIsTooSmall() throws Exception {
        DataSource dataSource = newDataSource("schema-separate-backup-modify");
        executeSql(dataSource,
                "create table user_account ("
                        + "id bigint primary key, "
                        + "id_card varchar(80), "
                        + "id_card_backup varchar(16))");

        MigrationSchemaSqlGenerator generator =
                new MigrationSchemaSqlGenerator(dataSource, metadataRegistry(), properties());

        List<String> ddl = generator.generateForEntity(
                SeparateTableUserEntity.class,
                builder -> builder.backupColumn("idCard", "id_card_backup"));

        assertEquals(Arrays.asList(
                "alter table `user_account` modify column `id_card_backup` varchar(80)",
                "create table `user_id_card_encrypt` (`id` varchar(64) primary key, "
                        + "`id_card_cipher` varchar(464), "
                        + "`id_card_hash` varchar(64), "
                        + "`id_card_like` varchar(80), "
                        + "`id_card_backup` varchar(80))"
        ), ddl);
    }

    /**
     * 测试目的：验证独立表模式会同时校验主表源列是否足以容纳迁移后写回的 hash 引用值。
     * 测试场景：主表身份证列长度只有 18，但迁移会把该列覆盖成 64 位 hash；断言生成器会先扩容主表源列，再输出备份列和独立表建表 SQL。
     */
    @Test
    void shouldModifySeparateTableSourceColumnWhenHashReferenceExceedsSourceLength() throws Exception {
        DataSource dataSource = newDataSource("schema-separate-source-modify");
        executeSql(dataSource,
                "create table user_account (id bigint primary key, id_card varchar(18))");

        MigrationSchemaSqlGenerator generator =
                new MigrationSchemaSqlGenerator(dataSource, metadataRegistry(), properties());

        List<String> ddl = generator.generateForEntity(
                SeparateTableUserEntity.class,
                builder -> builder.backupColumn("idCard", "id_card_backup"));

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

    /**
     * 测试目的：验证迁移 DDL 生成逻辑能按字段规则补齐密文列、脱敏列或独立表结构。
     * 测试场景：构造不同方言、字段长度和表结构状态，断言生成的建表/改表 SQL 符合迁移预期。
     */
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

    /**
     * 测试目的：验证迁移 DDL 生成逻辑能按字段规则补齐密文列、脱敏列或独立表结构。
     * 测试场景：构造不同方言、字段长度和表结构状态，断言生成的建表/改表 SQL 符合迁移预期。
     */
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

    /**
     * 测试目的：验证全量导出入口处理独立表模式时，也会读取全局备份列模板并补齐主表备份列 DDL。
     * 测试场景：注册身份证独立表加密实体，只调用 generateAllRegisteredTables，不传单实体 builder，断言主表备份列和缺失独立表建表 SQL 同时导出。
     */
    @Test
    void shouldGenerateSeparateTableBackupColumnWhenGeneratingAllRegisteredTables() throws Exception {
        DataSource dataSource = newDataSource("schema-batch-separate-ddl-with-backup");
        executeSql(dataSource,
                "create table user_account (id bigint primary key, id_card varchar(80))");
        io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry metadataRegistry = metadataRegistry();
        metadataRegistry.registerEntityType(SeparateTableUserEntity.class);
        io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties properties = properties();
        io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties.BackupColumnTemplateRuleProperties backupRule =
                new io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties.BackupColumnTemplateRuleProperties();
        backupRule.setTablePattern("user_account");
        backupRule.setFieldPattern("idCard|id_card");
        backupRule.setTemplate("${column}_backup");
        properties.getMigration().getBackupColumnTemplates().add(backupRule);

        MigrationSchemaSqlGenerator generator =
                new MigrationSchemaSqlGenerator(dataSource, metadataRegistry, properties);

        List<String> ddl = generator.generateAllRegisteredTables();

        assertEquals(Arrays.asList(
                "alter table `user_account` add column `id_card_backup` varchar(80) after `id_card`",
                "create table `user_id_card_encrypt` (`id` varchar(64) primary key, "
                        + "`id_card_cipher` varchar(464), "
                        + "`id_card_hash` varchar(64), "
                        + "`id_card_like` varchar(80), "
                        + "`id_card_backup` varchar(80))"
        ), ddl);
    }

    /**
     * 测试目的：验证全量导出入口在独立表覆盖主列并配置备份列时，会检查已存在备份列是否满足原始业务长度。
     * 测试场景：主表身份证列长度为 80，但历史备份列只有 16；只调用 generateAllRegisteredTables，断言返回 modify backup 和独立表建表 SQL。
     */
    @Test
    void shouldModifySeparateTableBackupColumnWhenGeneratingAllRegisteredTables() throws Exception {
        DataSource dataSource = newDataSource("schema-batch-separate-backup-modify");
        executeSql(dataSource,
                "create table user_account ("
                        + "id bigint primary key, "
                        + "id_card varchar(80), "
                        + "id_card_backup varchar(16))");
        io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry metadataRegistry = metadataRegistry();
        metadataRegistry.registerEntityType(SeparateTableUserEntity.class);
        io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties properties = properties();
        io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties.BackupColumnTemplateRuleProperties backupRule =
                new io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties.BackupColumnTemplateRuleProperties();
        backupRule.setTablePattern("user_account");
        backupRule.setFieldPattern("idCard|id_card");
        backupRule.setTemplate("${column}_backup");
        properties.getMigration().getBackupColumnTemplates().add(backupRule);

        MigrationSchemaSqlGenerator generator =
                new MigrationSchemaSqlGenerator(dataSource, metadataRegistry, properties);

        List<String> ddl = generator.generateAllRegisteredTables();

        assertEquals(Arrays.asList(
                "alter table `user_account` modify column `id_card_backup` varchar(80)",
                "create table `user_id_card_encrypt` (`id` varchar(64) primary key, "
                        + "`id_card_cipher` varchar(464), "
                        + "`id_card_hash` varchar(64), "
                        + "`id_card_like` varchar(80), "
                        + "`id_card_backup` varchar(80))"
        ), ddl);
    }

    /**
     * 测试目的：验证全量导出时多个主表共用一张独立表会先合并列需求，再输出一份取最大长度的最终 DDL。
     * 测试场景：用户表和归档表都映射到 shared_id_card_encrypt，但原字段长度分别为 80 和 120，断言只生成一条 create table，且密文和 LIKE 列长度取较大值。
     */
    @Test
    void shouldMergeSharedSeparateTableRequirementsAcrossRegisteredTables() throws Exception {
        DataSource dataSource = newDataSource("schema-batch-shared-separate-table");
        executeSql(dataSource,
                "create table user_account (id bigint primary key, id_card varchar(80))",
                "create table user_archive (id bigint primary key, archive_id_card varchar(120))");

        io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties properties = properties();

        io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties.TableRuleProperties accountRule =
                new io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties.TableRuleProperties();
        accountRule.setTable("user_account");
        io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties.FieldRuleProperties accountField =
                new io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties.FieldRuleProperties();
        accountField.setProperty("idCard");
        accountField.setColumn("id_card");
        accountField.setStorageMode(io.github.jasper.mybatis.encrypt.core.metadata.FieldStorageMode.SEPARATE_TABLE);
        accountField.setStorageTable("shared_id_card_encrypt");
        accountField.setStorageColumn("id_card_cipher");
        accountField.setStorageIdColumn("id");
        accountField.setAssistedQueryColumn("id_card_hash");
        accountField.setLikeQueryColumn("id_card_like");
        accountRule.getFields().add(accountField);
        properties.getTables().add(accountRule);

        io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties.TableRuleProperties archiveRule =
                new io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties.TableRuleProperties();
        archiveRule.setTable("user_archive");
        io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties.FieldRuleProperties archiveField =
                new io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties.FieldRuleProperties();
        archiveField.setProperty("archiveIdCard");
        archiveField.setColumn("archive_id_card");
        archiveField.setStorageMode(io.github.jasper.mybatis.encrypt.core.metadata.FieldStorageMode.SEPARATE_TABLE);
        archiveField.setStorageTable("shared_id_card_encrypt");
        archiveField.setStorageColumn("id_card_cipher");
        archiveField.setStorageIdColumn("id");
        archiveField.setAssistedQueryColumn("id_card_hash");
        archiveField.setLikeQueryColumn("id_card_like");
        archiveRule.getFields().add(archiveField);
        properties.getTables().add(archiveRule);

        io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry metadataRegistry =
                new io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry(
                        properties, new io.github.jasper.mybatis.encrypt.core.metadata.AnnotationEncryptMetadataLoader());

        MigrationSchemaSqlGenerator generator =
                new MigrationSchemaSqlGenerator(dataSource, metadataRegistry, properties);

        List<String> ddl = generator.generateAllRegisteredTables();

        assertEquals(Collections.singletonList(
                "create table `shared_id_card_encrypt` (`id` varchar(64) primary key, "
                        + "`id_card_cipher` varchar(680), "
                        + "`id_card_hash` varchar(64), "
                        + "`id_card_like` varchar(120))"
        ), ddl);
    }

    /**
     * 测试目的：验证共享独立表已经存在且字段长度不足时，全量导出只会生成一组最终扩容 SQL。
     * 测试场景：用户表和归档表共同映射到 shared_id_card_encrypt，现有外表列长度低于两边要求，断言生成器会合并需求后仅输出一次 modify，且长度取最大值。
     */
    @Test
    void shouldModifyExistingSharedSeparateTableUsingMergedMaximumLengths() throws Exception {
        DataSource dataSource = newDataSource("schema-batch-shared-separate-table-modify");
        executeSql(dataSource,
                "create table user_account (id bigint primary key, id_card varchar(80))",
                "create table user_archive (id bigint primary key, archive_id_card varchar(120))",
                "create table shared_id_card_encrypt ("
                        + "id varchar(32) primary key, "
                        + "id_card_cipher varchar(200), "
                        + "id_card_hash varchar(16), "
                        + "id_card_like varchar(40))");

        io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties properties = properties();

        io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties.TableRuleProperties accountRule =
                new io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties.TableRuleProperties();
        accountRule.setTable("user_account");
        io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties.FieldRuleProperties accountField =
                new io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties.FieldRuleProperties();
        accountField.setProperty("idCard");
        accountField.setColumn("id_card");
        accountField.setStorageMode(io.github.jasper.mybatis.encrypt.core.metadata.FieldStorageMode.SEPARATE_TABLE);
        accountField.setStorageTable("shared_id_card_encrypt");
        accountField.setStorageColumn("id_card_cipher");
        accountField.setStorageIdColumn("id");
        accountField.setAssistedQueryColumn("id_card_hash");
        accountField.setLikeQueryColumn("id_card_like");
        accountRule.getFields().add(accountField);
        properties.getTables().add(accountRule);

        io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties.TableRuleProperties archiveRule =
                new io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties.TableRuleProperties();
        archiveRule.setTable("user_archive");
        io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties.FieldRuleProperties archiveField =
                new io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties.FieldRuleProperties();
        archiveField.setProperty("archiveIdCard");
        archiveField.setColumn("archive_id_card");
        archiveField.setStorageMode(io.github.jasper.mybatis.encrypt.core.metadata.FieldStorageMode.SEPARATE_TABLE);
        archiveField.setStorageTable("shared_id_card_encrypt");
        archiveField.setStorageColumn("id_card_cipher");
        archiveField.setStorageIdColumn("id");
        archiveField.setAssistedQueryColumn("id_card_hash");
        archiveField.setLikeQueryColumn("id_card_like");
        archiveRule.getFields().add(archiveField);
        properties.getTables().add(archiveRule);

        io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry metadataRegistry =
                new io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry(
                        properties, new io.github.jasper.mybatis.encrypt.core.metadata.AnnotationEncryptMetadataLoader());

        MigrationSchemaSqlGenerator generator =
                new MigrationSchemaSqlGenerator(dataSource, metadataRegistry, properties);

        List<String> ddl = generator.generateAllRegisteredTables();

        assertEquals(Arrays.asList(
                "alter table `shared_id_card_encrypt` modify column `id_card_cipher` varchar(680)",
                "alter table `shared_id_card_encrypt` modify column `id_card_hash` varchar(64)",
                "alter table `shared_id_card_encrypt` modify column `id_card_like` varchar(120)"
        ), ddl);
    }

    /**
     * 测试目的：验证共享独立表同时承载 masked 列时，也会按多个来源字段长度合并需求且不重复输出 SQL。
     * 测试场景：两个主表共同映射 shared_id_card_encrypt，并都配置独立的 id_card_masked 列；现有外表 masked/like/cipher 列长度不足，断言全量导出仅输出一组按最大长度扩容后的 modify SQL。
     */
    @Test
    void shouldMergeMaskedColumnRequirementsForSharedSeparateTable() throws Exception {
        DataSource dataSource = newDataSource("schema-batch-shared-separate-table-masked-modify");
        executeSql(dataSource,
                "create table user_account (id bigint primary key, id_card varchar(80))",
                "create table user_archive (id bigint primary key, archive_id_card varchar(120))",
                "create table shared_id_card_encrypt ("
                        + "id varchar(64) primary key, "
                        + "id_card_cipher varchar(200), "
                        + "id_card_hash varchar(16), "
                        + "id_card_like varchar(40), "
                        + "id_card_masked varchar(20))");

        io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties properties = properties();

        io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties.TableRuleProperties accountRule =
                new io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties.TableRuleProperties();
        accountRule.setTable("user_account");
        io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties.FieldRuleProperties accountField =
                new io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties.FieldRuleProperties();
        accountField.setProperty("idCard");
        accountField.setColumn("id_card");
        accountField.setStorageMode(io.github.jasper.mybatis.encrypt.core.metadata.FieldStorageMode.SEPARATE_TABLE);
        accountField.setStorageTable("shared_id_card_encrypt");
        accountField.setStorageColumn("id_card_cipher");
        accountField.setStorageIdColumn("id");
        accountField.setAssistedQueryColumn("id_card_hash");
        accountField.setLikeQueryColumn("id_card_like");
        accountField.setMaskedColumn("id_card_masked");
        accountField.setMaskedAlgorithm("idCardMaskLike");
        accountRule.getFields().add(accountField);
        properties.getTables().add(accountRule);

        io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties.TableRuleProperties archiveRule =
                new io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties.TableRuleProperties();
        archiveRule.setTable("user_archive");
        io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties.FieldRuleProperties archiveField =
                new io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties.FieldRuleProperties();
        archiveField.setProperty("archiveIdCard");
        archiveField.setColumn("archive_id_card");
        archiveField.setStorageMode(io.github.jasper.mybatis.encrypt.core.metadata.FieldStorageMode.SEPARATE_TABLE);
        archiveField.setStorageTable("shared_id_card_encrypt");
        archiveField.setStorageColumn("id_card_cipher");
        archiveField.setStorageIdColumn("id");
        archiveField.setAssistedQueryColumn("id_card_hash");
        archiveField.setLikeQueryColumn("id_card_like");
        archiveField.setMaskedColumn("id_card_masked");
        archiveField.setMaskedAlgorithm("idCardMaskLike");
        archiveRule.getFields().add(archiveField);
        properties.getTables().add(archiveRule);

        io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry metadataRegistry =
                new io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry(
                        properties, new io.github.jasper.mybatis.encrypt.core.metadata.AnnotationEncryptMetadataLoader());

        MigrationSchemaSqlGenerator generator =
                new MigrationSchemaSqlGenerator(dataSource, metadataRegistry, properties);

        List<String> ddl = generator.generateAllRegisteredTables();

        assertEquals(Arrays.asList(
                "alter table `shared_id_card_encrypt` modify column `id_card_cipher` varchar(680)",
                "alter table `shared_id_card_encrypt` modify column `id_card_hash` varchar(64)",
                "alter table `shared_id_card_encrypt` modify column `id_card_like` varchar(120)",
                "alter table `shared_id_card_encrypt` modify column `id_card_masked` varchar(120)"
        ), ddl);
    }

    /**
     * 测试目的：验证数据迁移任务在同表模式和独立表模式下的完整执行结果。
     * 测试场景：准备源表、独立表和迁移状态目录，执行任务后校验密文数据、辅助列、检查点和报告统计。
     */
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

    /**
     * 测试目的：验证数据迁移任务在同表模式和独立表模式下的完整执行结果。
     * 测试场景：准备源表、独立表和迁移状态目录，执行任务后校验密文数据、辅助列、检查点和报告统计。
     */
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

    /**
     * 测试目的：验证迁移 DDL 生成逻辑能按字段规则补齐密文列、脱敏列或独立表结构。
     * 测试场景：构造不同方言、字段长度和表结构状态，断言生成的建表/改表 SQL 符合迁移预期。
     */
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

    /**
     * 测试目的：验证迁移配置、检查点或数据状态异常时能够安全拒绝执行。
     * 测试场景：构造异常的迁移定义、状态文件或源数据，断言任务快速失败且不会破坏已有迁移进度。
     */
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
