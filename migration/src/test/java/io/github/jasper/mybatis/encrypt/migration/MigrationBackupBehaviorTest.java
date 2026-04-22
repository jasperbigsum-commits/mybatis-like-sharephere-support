package io.github.jasper.mybatis.encrypt.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖原字段被覆盖时的备份行为，确保主表明文在原子更新前被安全保留。
 */
@DisplayName("迁移备份行为")
@Tag("unit")
@Tag("migration")
class MigrationBackupBehaviorTest extends MigrationJdbcTestSupport {

    @Test
    void shouldBackupPlaintextBeforeOverwritingSourceColumn() throws Exception {
        DataSource dataSource = newDataSource("separate_table_backup");
        executeSql(dataSource,
                "create table user_account (" +
                        "id bigint primary key, " +
                        "id_card varchar(64), " +
                        "id_card_backup varchar(64))",
                "create table user_id_card_encrypt (" +
                        "id varchar(64) primary key, " +
                        "id_card_cipher varchar(512), " +
                        "id_card_hash varchar(128), " +
                        "id_card_like varchar(255))",
                "insert into user_account (id, id_card) values (1, '320101199001011234')");

        MigrationTask task = JdbcMigrationTasks.create(
                dataSource,
                EntityMigrationDefinition.builder(SeparateTableUserEntity.class, "id")
                        .backupColumnByColumn("id_card", "id_card_backup")
                        .build(),
                metadataRegistry(),
                algorithmRegistry(),
                properties(),
                new FileMigrationStateStore(createTempDirectory("migration-state-separate-backup"))
        );

        MigrationReport report = task.execute();

        assertEquals(MigrationStatus.COMPLETED, report.getStatus());
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "select id_card, id_card_backup from user_account where id = 1")) {
            assertTrue(resultSet.next());
            assertNotEquals("320101199001011234", resultSet.getString("id_card"));
            assertEquals("320101199001011234", resultSet.getString("id_card_backup"));
        }
    }

    @Test
    void shouldBackupPlaintextBeforeReplacingSourceColumnWithHashValue() throws Exception {
        DataSource dataSource = newDataSource("same_table_hash_overwrite_backup");
        executeSql(dataSource,
                "create table user_account (" +
                        "id bigint primary key, " +
                        "phone varchar(128), " +
                        "phone_cipher varchar(512), " +
                        "phone_like varchar(255), " +
                        "phone_backup varchar(128))",
                "insert into user_account (id, phone) values (1, '13800138000')");

        MigrationTask task = JdbcMigrationTasks.create(
                dataSource,
                EntityMigrationDefinition.builder(HashOverwriteUserEntity.class, "id")
                        .backupColumn("phone", "phone_backup")
                        .build(),
                metadataRegistry(),
                algorithmRegistry(),
                properties(),
                new FileMigrationStateStore(createTempDirectory("migration-state-hash-overwrite-backup"))
        );

        MigrationReport report = task.execute();

        assertEquals(MigrationStatus.COMPLETED, report.getStatus());
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "select phone, phone_cipher, phone_like, phone_backup from user_account where id = 1")) {
            assertTrue(resultSet.next());
            String hashValue = resultSet.getString("phone");
            assertNotNull(hashValue);
            assertNotEquals("13800138000", hashValue);
            assertTrue(resultSet.getString("phone_cipher") != null && !resultSet.getString("phone_cipher").isEmpty());
            assertTrue(resultSet.getString("phone_like") != null && !resultSet.getString("phone_like").isEmpty());
            assertEquals("13800138000", resultSet.getString("phone_backup"));
        }
    }

    @Test
    void shouldBackupPlaintextBeforeReplacingSourceColumnWithLikeValue() throws Exception {
        DataSource dataSource = newDataSource("same_table_like_overwrite_backup");
        executeSql(dataSource,
                "create table user_account (" +
                        "id bigint primary key, " +
                        "phone varchar(255), " +
                        "phone_cipher varchar(512), " +
                        "phone_hash varchar(128), " +
                        "phone_backup varchar(128))",
                "insert into user_account (id, phone) values (1, ' AbC-123 ')");

        MigrationTask task = JdbcMigrationTasks.create(
                dataSource,
                EntityMigrationDefinition.builder(LikeOverwriteUserEntity.class, "id")
                        .backupColumn("phone", "phone_backup")
                        .build(),
                metadataRegistry(),
                algorithmRegistry(),
                properties(),
                new FileMigrationStateStore(createTempDirectory("migration-state-like-overwrite-backup"))
        );

        MigrationReport report = task.execute();

        assertEquals(MigrationStatus.COMPLETED, report.getStatus());
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "select phone, phone_cipher, phone_hash, phone_backup from user_account where id = 1")) {
            assertTrue(resultSet.next());
            String likeValue = resultSet.getString("phone");
            assertNotNull(likeValue);
            assertEquals("abc-123", likeValue);
            assertTrue(resultSet.getString("phone_cipher") != null && !resultSet.getString("phone_cipher").isEmpty());
            assertTrue(resultSet.getString("phone_hash") != null && !resultSet.getString("phone_hash").isEmpty());
            assertEquals(" AbC-123 ", resultSet.getString("phone_backup"));
        }
    }

    @Test
    void shouldResumeOverwriteMigrationFromBackupColumnWhenSourceWasAlreadyReplaced() throws Exception {
        DataSource dataSource = newDataSource("same_table_hash_overwrite_backup_resume");
        String plaintext = "13800138000";
        String hashValue = algorithmRegistry().assisted("sm3").transform(plaintext);
        executeSql(dataSource,
                "create table user_account (" +
                        "id bigint primary key, " +
                        "phone varchar(128), " +
                        "phone_cipher varchar(512), " +
                        "phone_like varchar(255), " +
                        "phone_backup varchar(128))",
                "insert into user_account (id, phone, phone_backup) values (1, '" + hashValue + "', '" + plaintext + "')");

        MigrationTask task = JdbcMigrationTasks.create(
                dataSource,
                EntityMigrationDefinition.builder(HashOverwriteUserEntity.class, "id")
                        .backupColumn("phone", "phone_backup")
                        .build(),
                metadataRegistry(),
                algorithmRegistry(),
                properties(),
                new FileMigrationStateStore(createTempDirectory("migration-state-hash-overwrite-backup-resume"))
        );

        MigrationReport report = task.execute();

        assertEquals(MigrationStatus.COMPLETED, report.getStatus());
        assertEquals(1L, report.getMigratedRows());
        assertEquals(1L, report.getVerifiedRows());
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "select phone, phone_cipher, phone_like, phone_backup from user_account where id = 1")) {
            assertTrue(resultSet.next());
            assertEquals(hashValue, resultSet.getString("phone"));
            assertTrue(resultSet.getString("phone_cipher") != null && !resultSet.getString("phone_cipher").isEmpty());
            assertEquals(algorithmRegistry().like("normalizedLike").transform(plaintext),
                    resultSet.getString("phone_like"));
            assertEquals(plaintext, resultSet.getString("phone_backup"));
        }
    }
}
