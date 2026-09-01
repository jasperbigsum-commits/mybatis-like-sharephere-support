package io.github.jasper.mybatis.encrypt.migration;

import io.github.jasper.mybatis.encrypt.migration.plan.EntityMigrationPlanFactory;
import io.github.jasper.mybatis.encrypt.migration.risk.MigrationRiskManifestFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖上线前风险确认的生成、批准与范围漂移拦截场景。
 */
@DisplayName("迁移确认策略")
@Tag("unit")
@Tag("migration")
class MigrationConfirmationPolicyTest extends MigrationJdbcTestSupport {

    /**
     * 测试目的：验证数据迁移任务在同表模式和独立表模式下的完整执行结果。
     * 测试场景：准备源表、独立表和迁移状态目录，执行任务后校验密文数据、辅助列、检查点和报告统计。
     */
    @Test
    void shouldCreateConfirmationTemplateAndBlockUntilApproved() throws Exception {
        DataSource dataSource = newDataSource("confirm_template");
        executeSql(dataSource,
                "create table user_account (" +
                        "id bigint primary key, " +
                        "phone varchar(64), " +
                        "phone_cipher varchar(512), " +
                        "phone_hash varchar(128), " +
                        "phone_like varchar(255))",
                "insert into user_account (id, phone) values (1, '13800138000')");

        Path confirmationDir = createTempDirectory("migration-confirm");
        MigrationConfirmationException exception = assertThrows(MigrationConfirmationException.class, () ->
                JdbcMigrationTasks.create(
                        dataSource,
                        EntityMigrationDefinition.builder(SameTableUserEntity.class, "id").build(),
                        metadataRegistry(),
                        algorithmRegistry(),
                        properties(),
                        new FileMigrationStateStore(createTempDirectory("migration-state-confirm")),
                        new FileMigrationConfirmationPolicy(confirmationDir)
        ).execute());

        assertEquals(MigrationErrorCode.CONFIRMATION_REQUIRED, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("approved=true"));
        Properties properties = loadSinglePropertiesFile(confirmationDir);
        assertEquals("false", properties.getProperty("approved"));
        assertEquals("id", properties.getProperty("cursorColumns.0"));
        assertEquals("UPDATE|user_account|phone_cipher", properties.getProperty("entry.1"));
        assertEquals("UPDATE|user_account|phone_hash", properties.getProperty("entry.2"));
        assertEquals("UPDATE|user_account|phone_like", properties.getProperty("entry.3"));
    }

    /**
     * 测试目的：验证数据迁移任务在同表模式和独立表模式下的完整执行结果。
     * 测试场景：准备源表、独立表和迁移状态目录，执行任务后校验密文数据、辅助列、检查点和报告统计。
     */
    @Test
    void shouldExecuteWhenConfirmationFileIsApprovedAndScopeMatches() throws Exception {
        DataSource dataSource = newDataSource("confirm_approved");
        executeSql(dataSource,
                "create table user_account (" +
                        "id bigint primary key, " +
                        "phone varchar(64), " +
                        "phone_cipher varchar(512), " +
                        "phone_hash varchar(128), " +
                        "phone_like varchar(255))",
                "insert into user_account (id, phone) values (1, '13800138000')");

        Path confirmationDir = createTempDirectory("migration-confirm-approved");
        EntityMigrationPlan plan = new EntityMigrationPlanFactory(metadataRegistry())
                .create(EntityMigrationDefinition.builder(SameTableUserEntity.class, "id").build());
        MigrationRiskManifest manifest = new MigrationRiskManifestFactory().create(plan);
        storeProperties(confirmationFile(confirmationDir, plan), confirmationProperties(manifest, true), "approved");

        MigrationReport report = JdbcMigrationTasks.create(
                dataSource,
                EntityMigrationDefinition.builder(SameTableUserEntity.class, "id").build(),
                metadataRegistry(),
                algorithmRegistry(),
                properties(),
                new FileMigrationStateStore(createTempDirectory("migration-state-confirm-approved")),
                new FileMigrationConfirmationPolicy(confirmationDir)
        ).execute();

        assertEquals(MigrationStatus.COMPLETED, report.getStatus());
        assertEquals(1L, report.getMigratedRows());
    }

    /**
     * 测试目的：验证迁移配置、检查点或数据状态异常时能够安全拒绝执行。
     * 测试场景：构造异常的迁移定义、状态文件或源数据，断言任务快速失败且不会破坏已有迁移进度。
     */
    @Test
    void shouldFailWhenConfirmationScopeDoesNotMatchActualMutationFields() throws Exception {
        DataSource dataSource = newDataSource("confirm_mismatch");
        executeSql(dataSource,
                "create table user_account (" +
                        "id bigint primary key, " +
                        "phone varchar(64), " +
                        "phone_cipher varchar(512), " +
                        "phone_hash varchar(128), " +
                        "phone_like varchar(255))",
                "insert into user_account (id, phone) values (1, '13800138000')");

        Path confirmationDir = createTempDirectory("migration-confirm-mismatch");
        FileMigrationConfirmationPolicy policy = new FileMigrationConfirmationPolicy(confirmationDir);
        EntityMigrationPlan plan = new EntityMigrationPlanFactory(metadataRegistry())
                .create(EntityMigrationDefinition.builder(SameTableUserEntity.class, "id").build());
        MigrationRiskManifest manifest = new MigrationRiskManifestFactory().create(plan);
        Properties config = new Properties();
        config.setProperty("approved", "true");
        config.setProperty("entityName", manifest.getEntityName());
        config.setProperty("tableName", manifest.getTableName());
        config.setProperty("cursorColumns.0", "id");
        config.setProperty("entry.1", "UPDATE|user_account|phone_cipher");
        config.setProperty("entry.2", "UPDATE|user_account|phone_hash");
        storeProperties(confirmationFile(confirmationDir, plan), config, "mismatch");

        MigrationConfirmationException exception = assertThrows(MigrationConfirmationException.class, () ->
                JdbcMigrationTasks.create(
                        dataSource,
                        EntityMigrationDefinition.builder(SameTableUserEntity.class, "id").build(),
                        metadataRegistry(),
                        algorithmRegistry(),
                        properties(),
                        new FileMigrationStateStore(createTempDirectory("migration-state-confirm-mismatch")),
                        policy
                ).execute());

        assertEquals(MigrationErrorCode.CONFIRMATION_SCOPE_MISMATCH, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("does not match actual mutation scope"));
    }

    /**
     * 测试目的：验证迁移配置、检查点或数据状态异常时能够安全拒绝执行。
     * 测试场景：构造异常的迁移定义、状态文件或源数据，断言任务快速失败且不会破坏已有迁移进度。
     */
    @Test
    void shouldFailWhenConfiguredExpectedScopeDiffersFromActualFields() throws Exception {
        DataSource dataSource = newDataSource("expected_scope_mismatch");
        executeSql(dataSource,
                "create table user_account (" +
                        "id bigint primary key, " +
                        "phone varchar(64), " +
                        "phone_cipher varchar(512), " +
                        "phone_hash varchar(128), " +
                        "phone_like varchar(255))",
                "insert into user_account (id, phone) values (1, '13800138000')");

        MigrationConfirmationException exception = assertThrows(MigrationConfirmationException.class, () ->
                JdbcMigrationTasks.create(
                        dataSource,
                        EntityMigrationDefinition.builder(SameTableUserEntity.class, "id").build(),
                        metadataRegistry(),
                        algorithmRegistry(),
                        properties(),
                        new FileMigrationStateStore(createTempDirectory("migration-state-expected-scope")),
                        ExpectedRiskConfirmationPolicy.of(
                                "UPDATE|user_account|phone_cipher",
                                "UPDATE|user_account|phone_hash"
                        )
                ).execute());

        assertEquals(MigrationErrorCode.CONFIRMATION_SCOPE_MISMATCH, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("does not match actual mutation scope"));
    }

    /**
     * 测试目的：验证数据迁移任务在同表模式和独立表模式下的完整执行结果。
     * 测试场景：准备源表、独立表和迁移状态目录，执行任务后校验密文数据、辅助列、检查点和报告统计。
     */
    @Test
    void shouldSupportOneExpectedRiskPolicyConfiguredForMultipleTables() throws Exception {
        DataSource dataSource = newDataSource("expected_scope_multi_table");
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

        EntityMigrationPlanFactory planFactory = new EntityMigrationPlanFactory(metadataRegistry());
        EntityMigrationPlan accountPlan = planFactory
                .create(EntityMigrationDefinition.builder(SameTableUserEntity.class, "id").build());
        EntityMigrationPlan archivePlan = planFactory
                .create(EntityMigrationDefinition.builder(ArchiveSameTableUserEntity.class, "id").build());
        MigrationRiskManifestFactory manifestFactory = new MigrationRiskManifestFactory();
        MigrationRiskManifest accountManifest = manifestFactory.create(accountPlan);
        MigrationRiskManifest archiveManifest = manifestFactory.create(archivePlan);

        ExpectedRiskConfirmationPolicy policy = ExpectedRiskConfirmationPolicy.builder()
                .expectEntityTable(accountPlan.getEntityName(), accountPlan.getTableName(),
                        toEntryTokens(accountManifest))
                .expectEntityTable(archivePlan.getEntityName(), archivePlan.getTableName(),
                        toEntryTokens(archiveManifest))
                .build();

        MigrationReport accountReport = JdbcMigrationTasks.create(
                dataSource,
                EntityMigrationDefinition.builder(SameTableUserEntity.class, "id").build(),
                metadataRegistry(),
                algorithmRegistry(),
                properties(),
                new FileMigrationStateStore(createTempDirectory("migration-state-expected-scope-account")),
                policy
        ).execute();

        MigrationReport archiveReport = JdbcMigrationTasks.create(
                dataSource,
                EntityMigrationDefinition.builder(ArchiveSameTableUserEntity.class, "id").build(),
                metadataRegistry(),
                algorithmRegistry(),
                properties(),
                new FileMigrationStateStore(createTempDirectory("migration-state-expected-scope-archive")),
                policy
        ).execute();

        assertEquals(MigrationStatus.COMPLETED, accountReport.getStatus());
        assertEquals(1L, accountReport.getMigratedRows());
        assertEquals(MigrationStatus.COMPLETED, archiveReport.getStatus());
        assertEquals(1L, archiveReport.getMigratedRows());
    }

    private String[] toEntryTokens(MigrationRiskManifest manifest) {
        List<String> tokens = new ArrayList<String>();
        for (MigrationRiskEntry entry : manifest.getEntries()) {
            tokens.add(entry.asToken());
        }
        return tokens.toArray(new String[0]);
    }
}
