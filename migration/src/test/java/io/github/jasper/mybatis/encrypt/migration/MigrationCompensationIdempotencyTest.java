package io.github.jasper.mybatis.encrypt.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖 checkpoint 回退后的幂等重放，确保重复批次不会再次写入副作用。
 */
@DisplayName("迁移补偿幂等性")
class MigrationCompensationIdempotencyTest extends MigrationJdbcTestSupport {

    @Test
    void shouldSkipAlreadyMigratedSeparateTableRowWhenCheckpointFallsBehind() throws Exception {
        DataSource dataSource = newDataSource("idempotent_separate_table");
        executeSql(dataSource,
                "create table user_account (" +
                        "id bigint primary key, " +
                        "id_card varchar(64))",
                "create table user_id_card_encrypt (" +
                        "id varchar(64) primary key, " +
                        "id_card_cipher varchar(512), " +
                        "id_card_hash varchar(128), " +
                        "id_card_like varchar(255))",
                "insert into user_account (id, id_card) values (1, '320101199001011234')");

        Path stateDir = createTempDirectory("migration-state-idempotent");
        FileMigrationStateStore stateStore = new FileMigrationStateStore(stateDir);
        MigrationTask firstTask = JdbcMigrationTasks.create(
                dataSource,
                EntityMigrationDefinition.builder(SeparateTableUserEntity.class, "id").build(),
                metadataRegistry(),
                algorithmRegistry(),
                properties(),
                stateStore
        );

        MigrationReport firstReport = firstTask.execute();
        assertEquals(1L, firstReport.getMigratedRows());

        EntityMigrationPlan plan = new io.github.jasper.mybatis.encrypt.migration.plan.EntityMigrationPlanFactory(metadataRegistry(), properties())
                .create(EntityMigrationDefinition.builder(SeparateTableUserEntity.class, "id").build());
        MigrationState staleState = stateStore.load(plan).orElseThrow(AssertionError::new);
        staleState.setStatus(MigrationStatus.READY);
        staleState.setLastProcessedCursorValues(Collections.<String>emptyList());
        staleState.setScannedRows(0L);
        staleState.setMigratedRows(0L);
        staleState.setSkippedRows(0L);
        staleState.setVerifiedRows(0L);
        stateStore.save(plan, staleState);

        MigrationTask replayTask = JdbcMigrationTasks.create(
                dataSource,
                EntityMigrationDefinition.builder(SeparateTableUserEntity.class, "id").build(),
                metadataRegistry(),
                algorithmRegistry(),
                properties(),
                stateStore
        );
        MigrationReport replayReport = replayTask.execute();

        assertEquals(MigrationStatus.COMPLETED, replayReport.getStatus());
        assertEquals(0L, replayReport.getMigratedRows());
        assertEquals(1L, replayReport.getSkippedRows());
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet mainResult = statement.executeQuery("select id_card from user_account where id = 1")) {
            assertTrue(mainResult.next());
            String referenceHash = mainResult.getString(1);
            assertNotNull(referenceHash);
            try (ResultSet externalCount = statement.executeQuery("select count(1) from user_id_card_encrypt")) {
                assertTrue(externalCount.next());
                assertEquals(1L, externalCount.getLong(1));
            }
        }
    }

    @Test
    void shouldFailFastWhenSeparateTableSourceWasOverwrittenButPlaintextCannotBeRecovered() throws Exception {
        DataSource dataSource = newDataSource("irrecoverable_separate_table");
        String idCardPlain = "320101199001011234";
        String idCardHash = algorithmRegistry().assisted("sm3").transform(idCardPlain);
        String idCardCipher = algorithmRegistry().cipher("sm4").encrypt(idCardPlain);
        executeSql(dataSource,
                "create table user_account (" +
                        "id bigint primary key, " +
                        "id_card varchar(64))",
                "create table user_id_card_encrypt (" +
                        "id varchar(64) primary key, " +
                        "id_card_cipher varchar(512), " +
                        "id_card_hash varchar(128), " +
                        "id_card_like varchar(255))",
                "insert into user_account (id, id_card) values (1, '" + idCardHash + "')",
                "insert into user_id_card_encrypt (id, id_card_cipher, id_card_hash, id_card_like) values ("
                        + "'ref-1', '" + idCardCipher + "', '" + idCardHash + "', null)");

        MigrationTask task = JdbcMigrationTasks.create(
                dataSource,
                EntityMigrationDefinition.builder(SeparateTableUserEntity.class, "id").build(),
                metadataRegistry(),
                algorithmRegistry(),
                properties(),
                new FileMigrationStateStore(createTempDirectory("migration-state-irrecoverable-separate"))
        );

        MigrationExecutionException exception = assertThrows(MigrationExecutionException.class, task::execute);

        assertEquals(MigrationErrorCode.PLAINTEXT_UNRECOVERABLE, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("id_card"));
    }

    @Test
    void shouldFailFastWhenSameTableOverwriteLostPlaintextDuringPartialReplay() throws Exception {
        DataSource dataSource = newDataSource("irrecoverable_same_table");
        String phonePlain = "13800138000";
        String phoneHash = algorithmRegistry().assisted("sm3").transform(phonePlain);
        String phoneCipher = algorithmRegistry().cipher("sm4").encrypt(phonePlain);
        executeSql(dataSource,
                "create table user_account (" +
                        "id bigint primary key, " +
                        "phone varchar(128), " +
                        "phone_cipher varchar(512), " +
                        "phone_like varchar(255))",
                "insert into user_account (id, phone, phone_cipher, phone_like) values ("
                        + "1, '" + phoneHash + "', '" + phoneCipher + "', null)");

        MigrationTask task = JdbcMigrationTasks.create(
                dataSource,
                EntityMigrationDefinition.builder(HashOverwriteUserEntity.class, "id").build(),
                metadataRegistry(),
                algorithmRegistry(),
                properties(),
                new FileMigrationStateStore(createTempDirectory("migration-state-irrecoverable-same"))
        );

        MigrationExecutionException exception = assertThrows(MigrationExecutionException.class, task::execute);

        assertEquals(MigrationErrorCode.PLAINTEXT_UNRECOVERABLE, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("phone"));
    }
}
