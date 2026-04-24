package io.github.jasper.mybatis.encrypt.migration;

import io.github.jasper.mybatis.encrypt.migration.jdbc.JdbcMigrationRecordWriter;
import io.github.jasper.mybatis.encrypt.migration.plan.EntityMigrationPlanFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    /**
     * 测试目的：验证覆盖式迁移、备份列和断点续跑的幂等安全行为。
     * 测试场景：准备已迁移或部分迁移的数据状态，执行迁移后校验备份明文、游标检查点和重复执行结果。
     */
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

    /**
     * 测试目的：验证独立表模式下的主表备份会先于外表插入和主表 hash 覆盖执行。
     * 测试场景：对真实 JDBC 连接做轻量代理，记录 SQL 执行顺序并断言备份更新先落在当前事务内。
     */
    @Test
    void shouldFlushBackupUpdateBeforeSeparateTableInsertAndSourceHashOverwrite() throws Exception {
        DataSource dataSource = newDataSource("separate_table_backup_order");
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

        EntityMigrationPlan plan = new EntityMigrationPlanFactory(metadataRegistry(), properties())
                .create(EntityMigrationDefinition.builder(SeparateTableUserEntity.class, "id")
                        .backupColumnByColumn("id_card", "id_card_backup")
                        .build());
        MigrationRecord record = new MigrationRecord(
                new MigrationCursor(Collections.<String, Object>singletonMap("id", Long.valueOf(1L))),
                Collections.<String, Object>singletonMap("id_card", "320101199001011234"));
        List<String> operations = new ArrayList<String>();

        try (Connection connection = dataSource.getConnection()) {
            Connection observedConnection = observingConnection(connection, operations);
            JdbcMigrationRecordWriter writer = new JdbcMigrationRecordWriter(
                    properties(),
                    algorithmRegistry(),
                    new ReferenceIdGenerator() {
                        @Override
                        public Object nextReferenceId(EntityMigrationColumnPlan plan, MigrationRecord currentRecord) {
                            return "ref-1";
                        }
                    });

            assertTrue(writer.write(observedConnection, plan, record));
        }

        int backupIndex = operations.indexOf("backup-update");
        int insertIndex = operations.indexOf("separate-insert");
        int sourceIndex = operations.indexOf("source-update");
        assertTrue(backupIndex >= 0, "backup update should be executed");
        assertTrue(insertIndex > backupIndex, "separate-table insert should happen after backup update");
        assertTrue(sourceIndex > insertIndex, "source hash overwrite should happen after separate-table insert");
    }

    /**
     * 测试目的：验证覆盖式迁移、备份列和断点续跑的幂等安全行为。
     * 测试场景：准备已迁移或部分迁移的数据状态，执行迁移后校验备份明文、游标检查点和重复执行结果。
     */
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

    /**
     * 测试目的：验证覆盖式迁移、备份列和断点续跑的幂等安全行为。
     * 测试场景：准备已迁移或部分迁移的数据状态，执行迁移后校验备份明文、游标检查点和重复执行结果。
     */
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

    /**
     * 测试目的：验证覆盖式迁移、备份列和断点续跑的幂等安全行为。
     * 测试场景：准备已迁移或部分迁移的数据状态，执行迁移后校验备份明文、游标检查点和重复执行结果。
     */
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

    /**
     * 测试目的：验证备份一致性校验不会误伤独立表模式下“源列已被 hash 覆盖、备份列保存原明文”的续跑场景。
     * 测试场景：准备只剩主表 hash 与备份明文的半迁移状态，重跑后应继续补齐独立表数据。
     */
    @Test
    void shouldResumeSeparateTableOverwriteFromBackupColumnWhenSourceWasAlreadyReplaced() throws Exception {
        DataSource dataSource = newDataSource("separate_table_backup_resume");
        String plaintext = "320101199001011234";
        String hashValue = algorithmRegistry().assisted("sm3").transform(plaintext);
        executeSql(dataSource,
                "create table user_account (" +
                        "id bigint primary key, " +
                        "id_card varchar(128), " +
                        "id_card_backup varchar(128))",
                "create table user_id_card_encrypt (" +
                        "id varchar(64) primary key, " +
                        "id_card_cipher varchar(512), " +
                        "id_card_hash varchar(128), " +
                        "id_card_like varchar(255))",
                "insert into user_account (id, id_card, id_card_backup) values (1, '" + hashValue + "', '" + plaintext + "')");

        MigrationTask task = JdbcMigrationTasks.create(
                dataSource,
                EntityMigrationDefinition.builder(SeparateTableUserEntity.class, "id")
                        .backupColumnByColumn("id_card", "id_card_backup")
                        .build(),
                metadataRegistry(),
                algorithmRegistry(),
                properties(),
                new FileMigrationStateStore(createTempDirectory("migration-state-separate-backup-resume"))
        );

        MigrationReport report = task.execute();

        assertEquals(MigrationStatus.COMPLETED, report.getStatus());
        assertEquals(1L, report.getMigratedRows());
        assertEquals(1L, report.getVerifiedRows());
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet mainResult = statement.executeQuery(
                     "select id_card, id_card_backup from user_account where id = 1")) {
            assertTrue(mainResult.next());
            assertEquals(hashValue, mainResult.getString("id_card"));
            assertEquals(plaintext, mainResult.getString("id_card_backup"));
            try (ResultSet externalResult = statement.executeQuery(
                    "select id_card_hash, id_card_like from user_id_card_encrypt where id_card_hash = '" + hashValue + "'")) {
                assertTrue(externalResult.next());
                assertEquals(hashValue, externalResult.getString("id_card_hash"));
                assertNotNull(externalResult.getString("id_card_like"));
            }
        }
    }

    private Connection observingConnection(Connection delegate, List<String> operations) {
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                if ("prepareStatement".equals(method.getName())
                        && args != null
                        && args.length > 0
                        && args[0] instanceof String) {
                    String sql = (String) args[0];
                    PreparedStatement statement = (PreparedStatement) method.invoke(delegate, args);
                    return observingStatement(statement, sql, operations);
                }
                return method.invoke(delegate, args);
            }
        };
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                handler);
    }

    private PreparedStatement observingStatement(PreparedStatement delegate,
                                                 String sql,
                                                 List<String> operations) {
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                if ("executeUpdate".equals(method.getName()) || "executeQuery".equals(method.getName())) {
                    String operation = classifyOperation(sql);
                    if (operation != null) {
                        operations.add(operation);
                    }
                }
                return method.invoke(delegate, args);
            }
        };
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                handler);
    }

    private String classifyOperation(String sql) {
        String normalizedSql = sql == null ? "" : sql.replaceAll("\\s+", " ").trim().toLowerCase();
        if (normalizedSql.startsWith("update ")
                && normalizedSql.contains("user_account")
                && normalizedSql.contains(" set ")
                && normalizedSql.contains("id_card_backup")) {
            return "backup-update";
        }
        if (normalizedSql.startsWith("insert into ")
                && normalizedSql.contains("user_id_card_encrypt")) {
            return "separate-insert";
        }
        if (normalizedSql.startsWith("update ")
                && normalizedSql.contains("user_account")
                && normalizedSql.contains(" set ")
                && normalizedSql.contains("id_card")
                && !normalizedSql.contains("id_card_backup")) {
            return "source-update";
        }
        return null;
    }
}
