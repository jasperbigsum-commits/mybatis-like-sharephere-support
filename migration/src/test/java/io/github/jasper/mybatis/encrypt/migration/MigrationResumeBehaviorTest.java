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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        Map<String, String> expectedRangeStart = new LinkedHashMap<String, String>();
        expectedRangeStart.put("tenant_id", "tenantA");
        expectedRangeStart.put("record_no", "1");
        Map<String, String> expectedRangeEnd = new LinkedHashMap<String, String>();
        expectedRangeEnd.put("tenant_id", "tenantB");
        expectedRangeEnd.put("record_no", "1");
        Map<String, String> expectedLastProcessed = new LinkedHashMap<String, String>();
        expectedLastProcessed.put("tenant_id", "tenantB");
        expectedLastProcessed.put("record_no", "1");
        assertEquals(expectedRangeStart, report.getRangeStartCursorMap());
        assertEquals(expectedRangeEnd, report.getRangeEndCursorMap());
        assertEquals("{tenant_id=tenantB, record_no=1}", report.getLastProcessedCursor());
        assertEquals(expectedLastProcessed, report.getLastProcessedCursorMap());
    }
}
