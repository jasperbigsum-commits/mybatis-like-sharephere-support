package io.github.jasper.mybatis.encrypt.migration;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.core.metadata.AnnotationEncryptMetadataLoader;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import io.github.jasper.mybatis.encrypt.migration.jdbc.JdbcEntityMigrationTask;
import io.github.jasper.mybatis.encrypt.migration.jdbc.JdbcMigrationRecordReader;
import io.github.jasper.mybatis.encrypt.migration.jdbc.JdbcMigrationRecordVerifier;
import io.github.jasper.mybatis.encrypt.migration.jdbc.JdbcMigrationRecordWriter;
import io.github.jasper.mybatis.encrypt.migration.plan.EntityMigrationPlanFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖断点恢复与复合游标场景，确保失败批次不会污染已提交检查点。
 */
@DisplayName("迁移恢复行为")
class MigrationResumeBehaviorTest extends MigrationJdbcTestSupport {

    @Test
    void shouldResumeFromLastCommittedBatchAfterFailure() throws Exception {
        DataSource dataSource = newDataSource("resume");
        executeSql(dataSource,
                "create table user_account (" +
                        "id bigint primary key, " +
                        "phone varchar(64), " +
                        "phone_cipher varchar(512), " +
                        "phone_hash varchar(128), " +
                        "phone_like varchar(255))",
                "insert into user_account (id, phone) values (1, '13800138000')",
                "insert into user_account (id, phone) values (2, '13900139000')",
                "insert into user_account (id, phone) values (3, '13700137000')");

        EncryptMetadataRegistry metadataRegistry = metadataRegistry();
        DatabaseEncryptionProperties properties = properties();
        AlgorithmRegistry algorithmRegistry = algorithmRegistry();
        Path stateDir = createTempDirectory("migration-state-resume");
        FileMigrationStateStore stateStore = new FileMigrationStateStore(stateDir);
        EntityMigrationPlan plan = new EntityMigrationPlanFactory(metadataRegistry)
                .create(EntityMigrationDefinition.builder(SameTableUserEntity.class, "id").batchSize(1).build());
        JdbcMigrationRecordReader reader = new JdbcMigrationRecordReader(properties);
        JdbcMigrationRecordVerifier verifier = new JdbcMigrationRecordVerifier(properties, algorithmRegistry);
        AtomicBoolean failOnce = new AtomicBoolean(true);
        MigrationRecordWriter unstableWriter = new MigrationRecordWriter() {
            private final JdbcMigrationRecordWriter delegate =
                    new JdbcMigrationRecordWriter(properties, algorithmRegistry, new SnowflakeReferenceIdGenerator());

            @Override
            public boolean write(Connection connection, EntityMigrationPlan currentPlan, MigrationRecord record)
                    throws java.sql.SQLException {
                if (Long.valueOf(2L).equals(record.getCursor().getPrimaryValue()) && failOnce.compareAndSet(true, false)) {
                    throw new MigrationException("intentional failure for resume");
                }
                return delegate.write(connection, currentPlan, record);
            }
        };

        JdbcEntityMigrationTask failingTask = new JdbcEntityMigrationTask(
                dataSource, plan, reader, reader, unstableWriter, verifier, stateStore,
                AllowAllMigrationConfirmationPolicy.INSTANCE);
        assertThrows(MigrationException.class, failingTask::execute);

        Properties failedState = loadSinglePropertiesFile(stateDir);
        assertEquals("FAILED", failedState.getProperty("status"));
        assertEquals("1", failedState.getProperty("lastProcessedId"));
        assertEquals("1", failedState.getProperty("scannedRows"));

        JdbcEntityMigrationTask resumedTask = new JdbcEntityMigrationTask(
                dataSource,
                plan,
                reader,
                reader,
                new JdbcMigrationRecordWriter(properties, algorithmRegistry, new SnowflakeReferenceIdGenerator()),
                verifier,
                stateStore,
                AllowAllMigrationConfirmationPolicy.INSTANCE
        );
        MigrationReport report = resumedTask.execute();

        assertEquals(MigrationStatus.COMPLETED, report.getStatus());
        assertEquals(3L, report.getScannedRows());
        assertEquals(3L, report.getMigratedRows());
        assertEquals(3L, report.getVerifiedRows());
        assertEquals("3", report.getLastProcessedCursor());
    }

    @Test
    void shouldResumeFromCompositeCursorCheckpoint() throws Exception {
        DataSource dataSource = newDataSource("resume_composite_cursor");
        executeSql(dataSource,
                "create table user_account (" +
                        "tenant_id varchar(32), " +
                        "record_no bigint, " +
                        "phone varchar(64), " +
                        "phone_cipher varchar(512), " +
                        "phone_hash varchar(128), " +
                        "phone_like varchar(255), " +
                        "primary key (tenant_id, record_no))",
                "insert into user_account (tenant_id, record_no, phone) values ('tenantA', 1, '13800138000')",
                "insert into user_account (tenant_id, record_no, phone) values ('tenantA', 2, '13900139000')",
                "insert into user_account (tenant_id, record_no, phone) values ('tenantB', 1, '13700137000')");

        DatabaseEncryptionProperties properties = configuredProperties();
        EncryptMetadataRegistry metadataRegistry = new EncryptMetadataRegistry(
                properties, new AnnotationEncryptMetadataLoader());
        AlgorithmRegistry algorithmRegistry = algorithmRegistry();
        Path stateDir = createTempDirectory("migration-state-resume-composite");
        FileMigrationStateStore stateStore = new FileMigrationStateStore(stateDir);
        EntityMigrationPlan plan = new EntityMigrationPlanFactory(metadataRegistry)
                .create(EntityMigrationDefinition.builder("user_account", "tenant_id", "record_no").batchSize(1).build());
        JdbcMigrationRecordReader reader = new JdbcMigrationRecordReader(properties);
        JdbcMigrationRecordVerifier verifier = new JdbcMigrationRecordVerifier(properties, algorithmRegistry);
        AtomicBoolean failOnce = new AtomicBoolean(true);
        MigrationRecordWriter unstableWriter = new MigrationRecordWriter() {
            private final JdbcMigrationRecordWriter delegate =
                    new JdbcMigrationRecordWriter(properties, algorithmRegistry, new SnowflakeReferenceIdGenerator());

            @Override
            public boolean write(Connection connection, EntityMigrationPlan currentPlan, MigrationRecord record)
                    throws java.sql.SQLException {
                if ("tenantA".equals(record.getCursor().getValue("tenant_id"))
                        && Long.valueOf(2L).equals(record.getCursor().getValue("record_no"))
                        && failOnce.compareAndSet(true, false)) {
                    throw new MigrationException("intentional composite cursor failure");
                }
                return delegate.write(connection, currentPlan, record);
            }
        };

        JdbcEntityMigrationTask failingTask = new JdbcEntityMigrationTask(
                dataSource, plan, reader, reader, unstableWriter, verifier, stateStore,
                AllowAllMigrationConfirmationPolicy.INSTANCE);
        assertThrows(MigrationException.class, failingTask::execute);

        Properties failedState = loadSinglePropertiesFile(stateDir);
        assertEquals("FAILED", failedState.getProperty("status"));
        assertEquals("tenantA", failedState.getProperty("lastProcessedCursorValues.0"));
        assertEquals("1", failedState.getProperty("lastProcessedCursorValues.1"));
        assertEquals("tenant_id", failedState.getProperty("cursorColumns.0"));
        assertEquals("record_no", failedState.getProperty("cursorColumns.1"));

        JdbcEntityMigrationTask resumedTask = new JdbcEntityMigrationTask(
                dataSource,
                plan,
                reader,
                reader,
                new JdbcMigrationRecordWriter(properties, algorithmRegistry, new SnowflakeReferenceIdGenerator()),
                verifier,
                stateStore,
                AllowAllMigrationConfirmationPolicy.INSTANCE
        );
        MigrationReport report = resumedTask.execute();

        assertEquals(MigrationStatus.COMPLETED, report.getStatus());
        assertEquals(3L, report.getScannedRows());
        assertEquals(3L, report.getMigratedRows());
        assertEquals(3L, report.getVerifiedRows());
        assertEquals(Arrays.asList("tenant_id", "record_no"), report.getCursorColumns());
        assertEquals(Arrays.asList("tenantB", "1"), report.getLastProcessedCursorValues());
        Map<String, String> expectedRangeStart = new LinkedHashMap<>();
        expectedRangeStart.put("tenant_id", "tenantA");
        expectedRangeStart.put("record_no", "1");
        Map<String, String> expectedRangeEnd = new LinkedHashMap<>();
        expectedRangeEnd.put("tenant_id", "tenantB");
        expectedRangeEnd.put("record_no", "1");
        Map<String, String> expectedLastProcessed = new LinkedHashMap<>();
        expectedLastProcessed.put("tenant_id", "tenantB");
        expectedLastProcessed.put("record_no", "1");
        assertEquals(expectedRangeStart, report.getRangeStartCursorMap());
        assertEquals(expectedRangeEnd, report.getRangeEndCursorMap());
        assertEquals("{tenant_id=tenantB, record_no=1}", report.getLastProcessedCursor());
        assertEquals(expectedLastProcessed, report.getLastProcessedCursorMap());
    }

    @Test
    void shouldRejectConcurrentExecutionWhenCheckpointLockIsHeld() throws Exception {
        DataSource dataSource = newDataSource("resume_lock");
        executeSql(dataSource,
                "create table user_account (" +
                        "id bigint primary key, " +
                        "phone varchar(64), " +
                        "phone_cipher varchar(512), " +
                        "phone_hash varchar(128), " +
                        "phone_like varchar(255))",
                "insert into user_account (id, phone) values (1, '13800138000')");

        DatabaseEncryptionProperties properties = properties();
        EncryptMetadataRegistry metadataRegistry = metadataRegistry();
        AlgorithmRegistry algorithmRegistry = algorithmRegistry();
        Path stateDir = createTempDirectory("migration-state-lock");
        FileMigrationStateStore stateStore = new FileMigrationStateStore(stateDir);
        EntityMigrationPlan plan = new EntityMigrationPlanFactory(metadataRegistry)
                .create(EntityMigrationDefinition.builder(SameTableUserEntity.class, "id").build());
        JdbcMigrationRecordReader reader = new JdbcMigrationRecordReader(properties);
        JdbcMigrationRecordVerifier verifier = new JdbcMigrationRecordVerifier(properties, algorithmRegistry);
        JdbcEntityMigrationTask task = new JdbcEntityMigrationTask(
                dataSource,
                plan,
                reader,
                reader,
                new JdbcMigrationRecordWriter(properties, algorithmRegistry, new SnowflakeReferenceIdGenerator()),
                verifier,
                stateStore,
                AllowAllMigrationConfirmationPolicy.INSTANCE
        );

        try (MigrationCheckpointLock ignored = stateStore.acquireCheckpointLock(plan)) {
            MigrationCheckpointLockException exception = assertThrows(MigrationCheckpointLockException.class, task::execute);
            assertEquals(MigrationErrorCode.CHECKPOINT_LOCKED, exception.getErrorCode());
        }
    }

    @Test
    void shouldSkipVerificationForAlreadyMigratedSeparateTableFieldWhenOtherFieldStillRequiresMigration() throws Exception {
        DataSource dataSource = newDataSource("resume_mixed_state_verify");
        executeSql(dataSource,
                "create table user_account (" +
                        "id bigint primary key, " +
                        "phone varchar(64), " +
                        "phone_cipher varchar(512), " +
                        "phone_hash varchar(128), " +
                        "phone_like varchar(255), " +
                        "id_card varchar(128))",
                "create table user_id_card_encrypt (" +
                        "id varchar(64) primary key, " +
                        "id_card_cipher varchar(512), " +
                        "id_card_hash varchar(128), " +
                        "id_card_like varchar(255))");

        AlgorithmRegistry algorithmRegistry = algorithmRegistry();
        String idCardPlain = "320101199001011234";
        String idCardHash = algorithmRegistry.assisted("sm3").transform(idCardPlain);
        String idCardCipher = algorithmRegistry.cipher("sm4").encrypt(idCardPlain);
        String idCardLike = algorithmRegistry.like("normalizedLike").transform(idCardPlain);
        executeSql(dataSource,
                "insert into user_account (id, phone, id_card) values (1, '13800138000', '" + idCardHash + "')",
                "insert into user_id_card_encrypt (id, id_card_cipher, id_card_hash, id_card_like) values ("
                        + "'ref-1', '" + idCardCipher + "', '" + idCardHash + "', '" + idCardLike + "')");

        Path stateDir = createTempDirectory("migration-state-mixed-verify");
        MigrationTask task = JdbcMigrationTasks.create(
                dataSource,
                EntityMigrationDefinition.builder(MixedStateUserEntity.class, "id").build(),
                metadataRegistry(),
                algorithmRegistry,
                properties(),
                new FileMigrationStateStore(stateDir)
        );

        MigrationReport report = task.execute();

        assertEquals(MigrationStatus.COMPLETED, report.getStatus());
        assertEquals(1L, report.getMigratedRows());
        assertEquals(1L, report.getVerifiedRows());
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet mainRow = statement.executeQuery(
                     "select phone_cipher, phone_hash, phone_like, id_card from user_account where id = 1")) {
            assertTrue(mainRow.next());
            assertEquals(idCardHash, mainRow.getString("id_card"));
            assertTrue(mainRow.getString("phone_cipher") != null && !mainRow.getString("phone_cipher").isEmpty());
            assertTrue(mainRow.getString("phone_hash") != null && !mainRow.getString("phone_hash").isEmpty());
            assertTrue(mainRow.getString("phone_like") != null && !mainRow.getString("phone_like").isEmpty());
            try (ResultSet externalCount = statement.executeQuery("select count(1) from user_id_card_encrypt")) {
                assertTrue(externalCount.next());
                assertEquals(1L, externalCount.getLong(1));
            }
        }
    }
}
