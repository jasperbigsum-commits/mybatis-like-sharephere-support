package io.github.jasper.mybatis.encrypt.migration;

import io.github.jasper.mybatis.encrypt.migration.plan.EntityMigrationPlanFactory;
import io.github.jasper.mybatis.encrypt.migration.risk.MigrationRiskManifestFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖上线前风险确认的生成、批准与范围漂移拦截场景。
 */
@DisplayName("迁移确认策略")
class MigrationConfirmationPolicyTest extends MigrationJdbcTestSupport {

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
}
