package io.github.jasper.mybatis.encrypt.migration;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.algorithm.support.NormalizedLikeQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.support.Sm3AssistedQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.support.Sm4CipherAlgorithm;
import io.github.jasper.mybatis.encrypt.annotation.EncryptField;
import io.github.jasper.mybatis.encrypt.annotation.EncryptTable;
import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.core.metadata.AnnotationEncryptMetadataLoader;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import io.github.jasper.mybatis.encrypt.core.metadata.FieldStorageMode;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.file.Files;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcEntityMigrationTaskTest {

    @Test
    void shouldMigrateSameTableColumnsAndPersistProgressFile() throws Exception {
        DataSource dataSource = newDataSource("same_table");
        executeSql(dataSource,
                "create table user_account (" +
                        "id bigint primary key, " +
                        "phone varchar(64), " +
                        "phone_cipher varchar(512), " +
                        "phone_hash varchar(128), " +
                        "phone_like varchar(255))",
                "insert into user_account (id, phone) values (1, '13800138000')",
                "insert into user_account (id, phone) values (2, '13900139000')");

        Path stateDir = Files.createTempDirectory("migration-state-same");
        MigrationTask task = JdbcMigrationTasks.create(
                dataSource,
                EntityMigrationDefinition.builder(SameTableUserEntity.class, "id").batchSize(1).build(),
                metadataRegistry(),
                algorithmRegistry(),
                properties(),
                new FileMigrationStateStore(stateDir)
        );

        MigrationReport report = task.execute();

        assertEquals(MigrationStatus.COMPLETED, report.getStatus());
        assertEquals(2L, report.getTotalRows());
        assertEquals(2L, report.getScannedRows());
        assertEquals(2L, report.getMigratedRows());
        assertEquals(2L, report.getVerifiedRows());
        assertEquals(0L, report.getSkippedRows());
        assertEquals("2", report.getLastProcessedCursor());

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "select phone, phone_cipher, phone_hash, phone_like from user_account where id = 1")) {
            assertTrue(resultSet.next());
            assertEquals("13800138000", resultSet.getString("phone"));
            assertTrue(resultSet.getString("phone_cipher") != null && !resultSet.getString("phone_cipher").isEmpty());
            assertTrue(resultSet.getString("phone_hash") != null && !resultSet.getString("phone_hash").isEmpty());
            assertTrue(resultSet.getString("phone_like") != null && !resultSet.getString("phone_like").isEmpty());
        }

        Path stateFile = Files.list(stateDir).findFirst().orElseThrow(AssertionError::new);
        Properties state = new Properties();
        try (java.io.InputStream inputStream = Files.newInputStream(stateFile)) {
            state.load(inputStream);
        }
        assertEquals("COMPLETED", state.getProperty("status"));
        assertEquals("2", state.getProperty("totalRows"));
        assertEquals("1", state.getProperty("rangeStart"));
        assertEquals("2", state.getProperty("rangeEnd"));
        assertEquals("2", state.getProperty("lastProcessedId"));
    }

    @Test
    void shouldMigrateSeparateTableColumnsAndReplaceMainColumnWithReferenceHash() throws Exception {
        DataSource dataSource = newDataSource("separate_table");
        executeSql(dataSource,
                "create table user_account (" +
                        "id bigint primary key, " +
                        "id_card varchar(64))",
                "create table user_id_card_encrypt (" +
                        "id varchar(64) primary key, " +
                        "id_card_cipher varchar(512), " +
                        "id_card_hash varchar(128), " +
                        "id_card_like varchar(255))",
                "insert into user_account (id, id_card) values (1, '320101199001011234')",
                "insert into user_account (id, id_card) values (2, '320101199001011235')");

        MigrationTask task = JdbcMigrationTasks.create(
                dataSource,
                EntityMigrationDefinition.builder(SeparateTableUserEntity.class, "id").build(),
                metadataRegistry(),
                algorithmRegistry(),
                properties(),
                new FileMigrationStateStore(Files.createTempDirectory("migration-state-separate"))
        );

        MigrationReport report = task.execute();

        assertEquals(MigrationStatus.COMPLETED, report.getStatus());
        assertEquals(2L, report.getMigratedRows());
        assertEquals(2L, report.getVerifiedRows());

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
            ResultSet mainResult = statement.executeQuery("select id_card from user_account where id = 1")) {
            assertTrue(mainResult.next());
            String migratedReferenceId = mainResult.getString(1);
            assertNotEquals("320101199001011234", migratedReferenceId);
            try (ResultSet externalResult = statement.executeQuery(
                    "select id_card_cipher, id_card_hash, id_card_like from user_id_card_encrypt where id_card_hash = '"
                            + migratedReferenceId + "'")) {
                assertTrue(externalResult.next());
                assertTrue(externalResult.getString("id_card_cipher") != null);
                assertEquals(migratedReferenceId, externalResult.getString("id_card_hash"));
                assertTrue(externalResult.getString("id_card_like") != null);
            }
        }
    }

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
                        .backupColumn("idCard", "id_card_backup")
                        .build(),
                metadataRegistry(),
                algorithmRegistry(),
                properties(),
                new FileMigrationStateStore(Files.createTempDirectory("migration-state-separate-backup"))
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
    void shouldMigrateConfiguredTableRuleByTableName() throws Exception {
        DataSource dataSource = newDataSource("config_table_name");
        executeSql(dataSource,
                "create table user_account (" +
                        "id bigint primary key, " +
                        "phone varchar(64), " +
                        "phone_cipher varchar(512), " +
                        "phone_hash varchar(128), " +
                        "phone_like varchar(255))",
                "insert into user_account (id, phone) values (1, '13800138000')");

        DatabaseEncryptionProperties properties = configuredProperties();
        MigrationTask task = JdbcMigrationTasks.create(
                dataSource,
                EntityMigrationDefinition.builder("user_account", "id").build(),
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                algorithmRegistry(),
                properties,
                new FileMigrationStateStore(Files.createTempDirectory("migration-state-config-table"))
        );

        MigrationReport report = task.execute();

        assertEquals(MigrationStatus.COMPLETED, report.getStatus());
        assertEquals("user_account", report.getEntityName());
        assertEquals(1L, report.getMigratedRows());
        assertEquals(1L, report.getVerifiedRows());
    }

    @Test
    void shouldMigrateUsingNonIdCursorColumnName() throws Exception {
        DataSource dataSource = newDataSource("non_id_cursor");
        executeSql(dataSource,
                "create table user_account (" +
                        "record_no bigint primary key, " +
                        "phone varchar(64), " +
                        "phone_cipher varchar(512), " +
                        "phone_hash varchar(128), " +
                        "phone_like varchar(255))",
                "insert into user_account (record_no, phone) values (10, '13800138000')",
                "insert into user_account (record_no, phone) values (11, '13900139000')");

        DatabaseEncryptionProperties properties = configuredProperties();
        MigrationTask task = JdbcMigrationTasks.create(
                dataSource,
                EntityMigrationDefinition.builder("user_account", "record_no").batchSize(1).build(),
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                algorithmRegistry(),
                properties,
                new FileMigrationStateStore(Files.createTempDirectory("migration-state-non-id-cursor"))
        );

        MigrationReport report = task.execute();

        assertEquals(MigrationStatus.COMPLETED, report.getStatus());
        assertEquals("11", report.getLastProcessedCursor());
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "select phone_cipher, phone_hash, phone_like from user_account where record_no = 10")) {
            assertTrue(resultSet.next());
            assertTrue(resultSet.getString("phone_cipher") != null && !resultSet.getString("phone_cipher").isEmpty());
            assertTrue(resultSet.getString("phone_hash") != null && !resultSet.getString("phone_hash").isEmpty());
            assertTrue(resultSet.getString("phone_like") != null && !resultSet.getString("phone_like").isEmpty());
        }
    }

    @Test
    void shouldMigrateAnnotatedTableRuleByTableNameAfterRegistryWarmUp() throws Exception {
        DataSource dataSource = newDataSource("annotation_table_name");
        executeSql(dataSource,
                "create table user_account (" +
                        "id bigint primary key, " +
                        "phone varchar(64), " +
                        "phone_cipher varchar(512), " +
                        "phone_hash varchar(128), " +
                        "phone_like varchar(255))",
                "insert into user_account (id, phone) values (1, '13800138000')");

        DatabaseEncryptionProperties properties = properties();
        EncryptMetadataRegistry metadataRegistry = new EncryptMetadataRegistry(
                properties, new AnnotationEncryptMetadataLoader());
        metadataRegistry.registerEntityType(SameTableUserEntity.class);

        MigrationTask task = JdbcMigrationTasks.create(
                dataSource,
                EntityMigrationDefinition.builder("user_account", "id").build(),
                metadataRegistry,
                algorithmRegistry(),
                properties,
                new FileMigrationStateStore(Files.createTempDirectory("migration-state-annotation-table"))
        );

        MigrationReport report = task.execute();

        assertEquals(MigrationStatus.COMPLETED, report.getStatus());
        assertEquals("user_account", report.getEntityName());
        assertEquals(1L, report.getMigratedRows());
        assertEquals(1L, report.getVerifiedRows());
    }

    @Test
    void shouldRejectDtoStyleMultiTableMetadata() {
        MigrationException exception = assertThrows(MigrationException.class, () ->
                new EntityMigrationPlanFactory(metadataRegistry()).create(
                        EntityMigrationDefinition.builder(MultiTableDto.class, "id").build()));

        assertTrue(exception.getMessage().contains("ignores DTO fields"));
    }

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
        Path stateDir = Files.createTempDirectory("migration-state-resume");
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

        Path stateFile = Files.list(stateDir).findFirst().orElseThrow(AssertionError::new);
        Properties failedState = new Properties();
        try (java.io.InputStream inputStream = Files.newInputStream(stateFile)) {
            failedState.load(inputStream);
        }
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
        Path stateDir = Files.createTempDirectory("migration-state-resume-composite");
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

        Path stateFile = Files.list(stateDir).findFirst().orElseThrow(AssertionError::new);
        Properties failedState = new Properties();
        try (java.io.InputStream inputStream = Files.newInputStream(stateFile)) {
            failedState.load(inputStream);
        }
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

        Path confirmationDir = Files.createTempDirectory("migration-confirm");
        MigrationException exception = assertThrows(MigrationException.class, () ->
                JdbcMigrationTasks.create(
                        dataSource,
                        EntityMigrationDefinition.builder(SameTableUserEntity.class, "id").build(),
                        metadataRegistry(),
                        algorithmRegistry(),
                        properties(),
                        new FileMigrationStateStore(Files.createTempDirectory("migration-state-confirm")),
                        new FileMigrationConfirmationPolicy(confirmationDir)
                ).execute());

        assertTrue(exception.getMessage().contains("approved=true"));
        Path confirmationFile = Files.list(confirmationDir).findFirst().orElseThrow(AssertionError::new);
        Properties properties = new Properties();
        try (java.io.InputStream inputStream = Files.newInputStream(confirmationFile)) {
            properties.load(inputStream);
        }
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

        Path confirmationDir = Files.createTempDirectory("migration-confirm-approved");
        EntityMigrationPlan plan = new EntityMigrationPlanFactory(metadataRegistry())
                .create(EntityMigrationDefinition.builder(SameTableUserEntity.class, "id").build());
        MigrationRiskManifest manifest = new MigrationRiskManifestFactory().create(plan);
        Path confirmationFile = confirmationDir.resolve(
                SameTableUserEntity.class.getName().replaceAll("[^a-zA-Z0-9._-]", "_")
                        + "__user_account.confirm.properties");
        Files.createDirectories(confirmationDir);
        Properties config = new Properties();
        config.setProperty("approved", "true");
        config.setProperty("entityName", manifest.getEntityName());
        config.setProperty("tableName", manifest.getTableName());
        for (int cursorIndex = 0; cursorIndex < manifest.getCursorColumns().size(); cursorIndex++) {
            config.setProperty("cursorColumns." + cursorIndex, manifest.getCursorColumns().get(cursorIndex));
        }
        int index = 1;
        for (MigrationRiskEntry entry : manifest.getEntries()) {
            config.setProperty("entry." + index++, entry.asToken());
        }
        try (java.io.OutputStream outputStream = Files.newOutputStream(confirmationFile)) {
            config.store(outputStream, "approved");
        }

        MigrationReport report = JdbcMigrationTasks.create(
                dataSource,
                EntityMigrationDefinition.builder(SameTableUserEntity.class, "id").build(),
                metadataRegistry(),
                algorithmRegistry(),
                properties(),
                new FileMigrationStateStore(Files.createTempDirectory("migration-state-confirm-approved")),
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

        Path confirmationDir = Files.createTempDirectory("migration-confirm-mismatch");
        FileMigrationConfirmationPolicy policy = new FileMigrationConfirmationPolicy(confirmationDir);
        EntityMigrationPlan plan = new EntityMigrationPlanFactory(metadataRegistry())
                .create(EntityMigrationDefinition.builder(SameTableUserEntity.class, "id").build());
        MigrationRiskManifest manifest = new MigrationRiskManifestFactory().create(plan);
        Path confirmationFile = confirmationDir.resolve(
                SameTableUserEntity.class.getName().replaceAll("[^a-zA-Z0-9._-]", "_")
                        + "__user_account.confirm.properties");
        Files.createDirectories(confirmationDir);
        Properties config = new Properties();
        config.setProperty("approved", "true");
        config.setProperty("entityName", manifest.getEntityName());
        config.setProperty("tableName", manifest.getTableName());
        config.setProperty("cursorColumns.0", "id");
        config.setProperty("entry.1", "UPDATE|user_account|phone_cipher");
        config.setProperty("entry.2", "UPDATE|user_account|phone_hash");
        try (java.io.OutputStream outputStream = Files.newOutputStream(confirmationFile)) {
            config.store(outputStream, "mismatch");
        }

        MigrationException exception = assertThrows(MigrationException.class, () ->
                JdbcMigrationTasks.create(
                        dataSource,
                        EntityMigrationDefinition.builder(SameTableUserEntity.class, "id").build(),
                        metadataRegistry(),
                        algorithmRegistry(),
                        properties(),
                        new FileMigrationStateStore(Files.createTempDirectory("migration-state-confirm-mismatch")),
                        policy
                ).execute());

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

        MigrationException exception = assertThrows(MigrationException.class, () ->
                JdbcMigrationTasks.create(
                        dataSource,
                        EntityMigrationDefinition.builder(SameTableUserEntity.class, "id").build(),
                        metadataRegistry(),
                        algorithmRegistry(),
                        properties(),
                        new FileMigrationStateStore(Files.createTempDirectory("migration-state-expected-scope")),
                        ExpectedRiskConfirmationPolicy.of(
                                "UPDATE|user_account|phone_cipher",
                                "UPDATE|user_account|phone_hash"
                        )
                ).execute());

        assertTrue(exception.getMessage().contains("does not match actual mutation scope"));
    }

    private DataSource newDataSource(String name) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private void executeSql(DataSource dataSource, String... sqlList) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : sqlList) {
                statement.execute(sql);
            }
        }
    }

    private EncryptMetadataRegistry metadataRegistry() {
        return new EncryptMetadataRegistry(properties(), new AnnotationEncryptMetadataLoader());
    }

    private DatabaseEncryptionProperties properties() {
        DatabaseEncryptionProperties properties = new DatabaseEncryptionProperties();
        properties.setDefaultCipherKey("unit-test-key-123");
        return properties;
    }

    private DatabaseEncryptionProperties configuredProperties() {
        DatabaseEncryptionProperties properties = properties();
        DatabaseEncryptionProperties.TableRuleProperties tableRule =
                new DatabaseEncryptionProperties.TableRuleProperties();
        tableRule.setTable("user_account");
        DatabaseEncryptionProperties.FieldRuleProperties phoneRule =
                new DatabaseEncryptionProperties.FieldRuleProperties();
        phoneRule.setColumn("phone");
        phoneRule.setStorageColumn("phone_cipher");
        phoneRule.setAssistedQueryColumn("phone_hash");
        phoneRule.setLikeQueryColumn("phone_like");
        tableRule.getFields().add(phoneRule);
        properties.getTables().add(tableRule);
        return properties;
    }

    private AlgorithmRegistry algorithmRegistry() {
        Map<String, io.github.jasper.mybatis.encrypt.algorithm.CipherAlgorithm> cipherAlgorithms =
                new LinkedHashMap<String, io.github.jasper.mybatis.encrypt.algorithm.CipherAlgorithm>();
        cipherAlgorithms.put("sm4", new Sm4CipherAlgorithm("unit-test-key-123"));
        Map<String, io.github.jasper.mybatis.encrypt.algorithm.AssistedQueryAlgorithm> assistedAlgorithms =
                new LinkedHashMap<String, io.github.jasper.mybatis.encrypt.algorithm.AssistedQueryAlgorithm>();
        assistedAlgorithms.put("sm3", new Sm3AssistedQueryAlgorithm());
        Map<String, io.github.jasper.mybatis.encrypt.algorithm.LikeQueryAlgorithm> likeAlgorithms =
                new LinkedHashMap<String, io.github.jasper.mybatis.encrypt.algorithm.LikeQueryAlgorithm>();
        likeAlgorithms.put("normalizedLike", new NormalizedLikeQueryAlgorithm());
        return new AlgorithmRegistry(cipherAlgorithms, assistedAlgorithms, likeAlgorithms);
    }

    @EncryptTable("user_account")
    static class SameTableUserEntity {

        private Long id;

        @EncryptField(
                column = "phone",
                storageColumn = "phone_cipher",
                assistedQueryColumn = "phone_hash",
                likeQueryColumn = "phone_like"
        )
        private String phone;
    }

    @EncryptTable("user_account")
    static class SeparateTableUserEntity {

        private Long id;

        @EncryptField(
                column = "id_card",
                storageMode = FieldStorageMode.SEPARATE_TABLE,
                storageTable = "user_id_card_encrypt",
                storageColumn = "id_card_cipher",
                storageIdColumn = "id",
                assistedQueryColumn = "id_card_hash",
                likeQueryColumn = "id_card_like"
        )
        private String idCard;
    }

    static class MultiTableDto {

        private Long id;

        @EncryptField(
                table = "user_account",
                column = "phone",
                storageColumn = "phone_cipher"
        )
        private String phone;

        @EncryptField(
                table = "user_archive",
                column = "archive_phone",
                storageColumn = "archive_phone_cipher"
        )
        private String archivePhone;
    }
}
