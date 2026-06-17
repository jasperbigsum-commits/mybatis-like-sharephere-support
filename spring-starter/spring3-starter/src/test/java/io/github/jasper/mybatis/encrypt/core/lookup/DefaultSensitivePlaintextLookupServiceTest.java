package io.github.jasper.mybatis.encrypt.core.lookup;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.algorithm.support.NormalizedLikeQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.support.PhoneNumberMaskLikeQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.support.Sm3AssistedQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.support.Sm4CipherAlgorithm;
import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.core.mask.SensitiveDataContext;
import io.github.jasper.mybatis.encrypt.core.mask.SensitiveResponseStrategy;
import io.github.jasper.mybatis.encrypt.core.metadata.AnnotationEncryptMetadataLoader;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import io.github.jasper.mybatis.encrypt.exception.EncryptionException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import jakarta.persistence.Column;
import jakarta.persistence.Table;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("unit")
@Tag("lookup")
class DefaultSensitivePlaintextLookupServiceTest {

    @Test
    void shouldLookupPlaintextByLookupMetaForSameTableField() throws Exception {
        DataSource dataSource = dataSource("lookup_plaintext_same_table");
        String plainValue = "13800138000";
        Sm4CipherAlgorithm sm4 = new Sm4CipherAlgorithm("unit-test-key");
        String cipherValue = sm4.encrypt(plainValue);
        String lookupHash = new Sm3AssistedQueryAlgorithm().transform(plainValue);

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("create table user_account (" +
                    "id varchar(64) primary key," +
                    "phone varchar(255)," +
                    "phone_hash varchar(255)," +
                    "name varchar(64))");
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "insert into user_account (id, phone, phone_hash, name) values (?, ?, ?, ?)")) {
            statement.setString(1, "U-100");
            statement.setString(2, cipherValue);
            statement.setString(3, lookupHash);
            statement.setString(4, "alice");
            statement.executeUpdate();
        }

        DefaultSensitivePlaintextLookupService service = new DefaultSensitivePlaintextLookupService(
                Map.of("dataSource", dataSource),
                metadataRegistry(),
                algorithms(),
                properties(),
                SensitivePlaintextAuditRecorder.noOp()
        );

        SensitiveDataContext.SensitiveLookupMeta lookupMeta =
                new SensitiveDataContext.SensitiveLookupMeta("sid_09d8fcbf4d87731eb9ce135bf183957be0b2d5c7f04258dfcaadbe7619d3c755",
                        "pid_12790c679f20d3fef946e2f8b815e3f63f93d5e0dc94a7bea0f8f5d8c0b9fcd7",
                        "U-100",
                        lookupHash);

        assertEquals(plainValue, service.lookup(lookupMeta));
    }

    @Test
    void shouldRecordSuccessWhenExplicitLookupSucceeds() throws Exception {
        DataSource dataSource = dataSource("lookup_plaintext_success_audit");
        String plainValue = "13800138010";
        Sm4CipherAlgorithm sm4 = new Sm4CipherAlgorithm("unit-test-key");
        String cipherValue = sm4.encrypt(plainValue);
        String lookupHash = new Sm3AssistedQueryAlgorithm().transform(plainValue);

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("create table user_account (" +
                    "id varchar(64) primary key," +
                    "phone varchar(255)," +
                    "phone_hash varchar(255)," +
                    "name varchar(64))");
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "insert into user_account (id, phone, phone_hash, name) values (?, ?, ?, ?)")) {
            statement.setString(1, "U-110");
            statement.setString(2, cipherValue);
            statement.setString(3, lookupHash);
            statement.setString(4, "alice");
            statement.executeUpdate();
        }
        AtomicReference<SensitivePlaintextAuditEvent> auditEvent = new AtomicReference<SensitivePlaintextAuditEvent>();

        DefaultSensitivePlaintextLookupService service = new DefaultSensitivePlaintextLookupService(
                Map.of("dataSource", dataSource),
                metadataRegistry(),
                algorithms(),
                properties(),
                auditEvent::set
        );

        SensitiveDataContext.SensitiveLookupMeta lookupMeta =
                new SensitiveDataContext.SensitiveLookupMeta("sid_09d8fcbf4d87731eb9ce135bf183957be0b2d5c7f04258dfcaadbe7619d3c755",
                        "pid_12790c679f20d3fef946e2f8b815e3f63f93d5e0dc94a7bea0f8f5d8c0b9fcd7",
                        "U-110",
                        lookupHash);

        assertEquals(plainValue, service.lookup(lookupMeta));
        assertEquals(true, auditEvent.get().isSuccess());
        assertEquals("user_account", auditEvent.get().getTableName());
        assertEquals("phone", auditEvent.get().getPropertyName());
        assertEquals("phone", auditEvent.get().getColumnName());
        assertEquals(lookupMeta, auditEvent.get().getLookupMeta());
        assertEquals(plainValue, auditEvent.get().getPlaintext());
        assertEquals(null, auditEvent.get().getErrorCode());
    }

    @Test
    void shouldNotRecordAuditWhenInternalLookupSucceeds() throws Exception {
        DataSource dataSource = dataSource("lookup_plaintext_internal_no_audit");
        String plainValue = "13800138011";
        Sm4CipherAlgorithm sm4 = new Sm4CipherAlgorithm("unit-test-key");
        String cipherValue = sm4.encrypt(plainValue);
        String lookupHash = new Sm3AssistedQueryAlgorithm().transform(plainValue);

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("create table user_account (" +
                    "id varchar(64) primary key," +
                    "phone varchar(255)," +
                    "phone_hash varchar(255)," +
                    "name varchar(64))");
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "insert into user_account (id, phone, phone_hash, name) values (?, ?, ?, ?)")) {
            statement.setString(1, "U-111");
            statement.setString(2, cipherValue);
            statement.setString(3, lookupHash);
            statement.setString(4, "alice");
            statement.executeUpdate();
        }
        AtomicReference<SensitivePlaintextAuditEvent> auditEvent = new AtomicReference<SensitivePlaintextAuditEvent>();

        DefaultSensitivePlaintextLookupService service = new DefaultSensitivePlaintextLookupService(
                Map.of("dataSource", dataSource),
                metadataRegistry(),
                algorithms(),
                properties(),
                auditEvent::set
        );

        SensitiveDataContext.SensitiveLookupMeta lookupMeta =
                new SensitiveDataContext.SensitiveLookupMeta("sid_09d8fcbf4d87731eb9ce135bf183957be0b2d5c7f04258dfcaadbe7619d3c755",
                        "pid_12790c679f20d3fef946e2f8b815e3f63f93d5e0dc94a7bea0f8f5d8c0b9fcd7",
                        "U-111",
                        lookupHash);

        assertEquals(plainValue, service.lookupInternal(lookupMeta));
        assertEquals(null, auditEvent.get());
    }

    @Test
    void shouldReuseCurrentSensitiveContextWhenInternalLookupMetaAlreadyRecorded() {
        AtomicReference<SensitivePlaintextAuditEvent> auditEvent = new AtomicReference<SensitivePlaintextAuditEvent>();
        DefaultSensitivePlaintextLookupService service = new DefaultSensitivePlaintextLookupService(
                Collections.<String, DataSource>emptyMap(),
                metadataRegistry(),
                algorithms(),
                properties(),
                auditEvent::set
        );

        String plainValue = "13800138012";
        SensitiveDataContext.SensitiveLookupMeta lookupMeta =
                new SensitiveDataContext.SensitiveLookupMeta("sid_09d8fcbf4d87731eb9ce135bf183957be0b2d5c7f04258dfcaadbe7619d3c755",
                        "pid_12790c679f20d3fef946e2f8b815e3f63f93d5e0dc94a7bea0f8f5d8c0b9fcd7",
                        "U-112",
                        new Sm3AssistedQueryAlgorithm().transform(plainValue));

        try (SensitiveDataContext.Scope ignored = SensitiveDataContext.open(false, SensitiveResponseStrategy.RECORDED_ONLY)) {
            SensitiveDataContext.record(new Object(), "phone", plainValue, null, lookupMeta);

            assertEquals(plainValue, service.lookupInternal(lookupMeta));
        }
        assertEquals(null, auditEvent.get());
    }

    @Test
    void shouldKeepExplicitLookupDatabaseBackedEvenWhenCurrentContextHasPlaintext() {
        AtomicReference<SensitivePlaintextAuditEvent> auditEvent = new AtomicReference<SensitivePlaintextAuditEvent>();
        DefaultSensitivePlaintextLookupService service = new DefaultSensitivePlaintextLookupService(
                Collections.<String, DataSource>emptyMap(),
                metadataRegistry(),
                algorithms(),
                properties(),
                auditEvent::set
        );

        String plainValue = "13800138013";
        SensitiveDataContext.SensitiveLookupMeta lookupMeta =
                new SensitiveDataContext.SensitiveLookupMeta("sid_09d8fcbf4d87731eb9ce135bf183957be0b2d5c7f04258dfcaadbe7619d3c755",
                        "pid_12790c679f20d3fef946e2f8b815e3f63f93d5e0dc94a7bea0f8f5d8c0b9fcd7",
                        "U-113",
                        new Sm3AssistedQueryAlgorithm().transform(plainValue));

        try (SensitiveDataContext.Scope ignored = SensitiveDataContext.open(false, SensitiveResponseStrategy.RECORDED_ONLY)) {
            SensitiveDataContext.record(new Object(), "phone", plainValue, null, lookupMeta);

            assertThrows(EncryptionException.class, () -> service.lookup(lookupMeta));
        }
        assertEquals(false, auditEvent.get().isSuccess());
        assertEquals("GENERAL_FAILURE", auditEvent.get().getErrorCode());
        assertEquals("user_account", auditEvent.get().getTableName());
        assertEquals("phone", auditEvent.get().getPropertyName());
        assertEquals("phone", auditEvent.get().getColumnName());
        assertEquals(null, auditEvent.get().getPlaintext());
    }

    @Test
    void shouldNotRecordAuditWhenInternalLookupFails() {
        AtomicReference<SensitivePlaintextAuditEvent> auditEvent = new AtomicReference<SensitivePlaintextAuditEvent>();
        DefaultSensitivePlaintextLookupService service = new DefaultSensitivePlaintextLookupService(
                Collections.<String, DataSource>emptyMap(),
                metadataRegistry(),
                algorithms(),
                properties(),
                auditEvent::set
        );

        SensitiveDataContext.SensitiveLookupMeta lookupMeta =
                new SensitiveDataContext.SensitiveLookupMeta("sid", "pid", null, "hash");

        assertThrows(EncryptionException.class, () -> service.lookupInternal(lookupMeta));
        assertEquals(null, auditEvent.get());
    }

    @Test
    void shouldRejectLookupWhenLookupMetaIsIncompleteAndRecordFailure() {
        AtomicReference<SensitivePlaintextAuditEvent> auditEvent = new AtomicReference<SensitivePlaintextAuditEvent>();
        DefaultSensitivePlaintextLookupService service = new DefaultSensitivePlaintextLookupService(
                Collections.<String, DataSource>emptyMap(),
                metadataRegistry(),
                algorithms(),
                properties(),
                auditEvent::set
        );

        SensitiveDataContext.SensitiveLookupMeta lookupMeta =
                new SensitiveDataContext.SensitiveLookupMeta("sid", "pid", null, "hash");

        assertThrows(EncryptionException.class, () -> service.lookup(lookupMeta));
        assertEquals(false, auditEvent.get().isSuccess());
        assertEquals("INVALID_FIELD_RULE", auditEvent.get().getErrorCode());
        assertEquals(lookupMeta, auditEvent.get().getLookupMeta());
        assertEquals(null, auditEvent.get().getTableName());
        assertEquals(null, auditEvent.get().getPropertyName());
        assertEquals(null, auditEvent.get().getColumnName());
    }

    @Test
    void shouldRecordCallerProvidedAuditAttributesWhenExplicitLookupSucceeds() throws Exception {
        DataSource dataSource = dataSource("lookup_plaintext_custom_audit_attributes");
        String plainValue = "13800138014";
        Sm4CipherAlgorithm sm4 = new Sm4CipherAlgorithm("unit-test-key");
        String cipherValue = sm4.encrypt(plainValue);
        String lookupHash = new Sm3AssistedQueryAlgorithm().transform(plainValue);

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("create table user_account (" +
                    "id varchar(64) primary key," +
                    "phone varchar(255)," +
                    "phone_hash varchar(255)," +
                    "name varchar(64))");
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "insert into user_account (id, phone, phone_hash, name) values (?, ?, ?, ?)")) {
            statement.setString(1, "U-114");
            statement.setString(2, cipherValue);
            statement.setString(3, lookupHash);
            statement.setString(4, "alice");
            statement.executeUpdate();
        }
        AtomicReference<SensitivePlaintextAuditEvent> auditEvent = new AtomicReference<SensitivePlaintextAuditEvent>();

        DefaultSensitivePlaintextLookupService service = new DefaultSensitivePlaintextLookupService(
                Map.of("dataSource", dataSource),
                metadataRegistry(),
                algorithms(),
                properties(),
                auditEvent::set
        );

        SensitiveDataContext.SensitiveLookupMeta lookupMeta =
                new SensitiveDataContext.SensitiveLookupMeta("sid_09d8fcbf4d87731eb9ce135bf183957be0b2d5c7f04258dfcaadbe7619d3c755",
                        "pid_12790c679f20d3fef946e2f8b815e3f63f93d5e0dc94a7bea0f8f5d8c0b9fcd7",
                        "U-114",
                        lookupHash);

        assertEquals(plainValue, service.lookup(lookupMeta,
                Collections.<String, Object>singletonMap("ticketNo", "T-001")));
        assertEquals("T-001", auditEvent.get().getAttributes().get("ticketNo"));
    }

    @Test
    void shouldRecordCallerProvidedAuditAttributesWhenExplicitLookupFails() {
        AtomicReference<SensitivePlaintextAuditEvent> auditEvent = new AtomicReference<SensitivePlaintextAuditEvent>();
        DefaultSensitivePlaintextLookupService service = new DefaultSensitivePlaintextLookupService(
                Collections.<String, DataSource>emptyMap(),
                metadataRegistry(),
                algorithms(),
                properties(),
                auditEvent::set
        );

        SensitiveDataContext.SensitiveLookupMeta lookupMeta =
                new SensitiveDataContext.SensitiveLookupMeta("sid_09d8fcbf4d87731eb9ce135bf183957be0b2d5c7f04258dfcaadbe7619d3c755",
                        "pid_12790c679f20d3fef946e2f8b815e3f63f93d5e0dc94a7bea0f8f5d8c0b9fcd7",
                        "U-115",
                        "hash");

        assertThrows(EncryptionException.class, () -> service.lookup(lookupMeta,
                Collections.<String, Object>singletonMap("ticketNo", "T-002")));

        assertEquals(false, auditEvent.get().isSuccess());
        assertEquals("T-002", auditEvent.get().getAttributes().get("ticketNo"));
    }

    @Test
    void shouldLookupPlaintextUsingResolvedBusinessKeyColumn() throws Exception {
        DataSource dataSource = dataSource("lookup_plaintext_custom_business_column");
        String plainValue = "13900139000";
        Sm4CipherAlgorithm sm4 = new Sm4CipherAlgorithm("unit-test-key");
        String cipherValue = sm4.encrypt(plainValue);
        String lookupHash = new Sm3AssistedQueryAlgorithm().transform(plainValue);

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("create table custom_user_account (" +
                    "tenant_user_id varchar(64) primary key," +
                    "phone varchar(255)," +
                    "phone_hash varchar(255))");
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "insert into custom_user_account (tenant_user_id, phone, phone_hash) values (?, ?, ?)")) {
            statement.setString(1, "TENANT-1");
            statement.setString(2, cipherValue);
            statement.setString(3, lookupHash);
            statement.executeUpdate();
        }

        DefaultSensitivePlaintextLookupService service = new DefaultSensitivePlaintextLookupService(
                Map.of("dataSource", dataSource),
                metadataRegistryWithCustomBusinessColumn(),
                algorithms(),
                customBusinessColumnProperties(),
                SensitivePlaintextAuditRecorder.noOp()
        );

        SensitiveDataContext.SensitiveLookupMeta lookupMeta =
                new SensitiveDataContext.SensitiveLookupMeta("sid_custom_user_account",
                        "pid_custom_user_account_phone",
                        "TENANT-1",
                        lookupHash);

        assertEquals(plainValue, service.lookup(lookupMeta));
    }

    @Test
    void shouldRejectLookupWhenMultipleDataSourcesExist() {
        DefaultSensitivePlaintextLookupService service = new DefaultSensitivePlaintextLookupService(
                Map.of("first", dataSource("lookup_multi_ds_1"), "second", dataSource("lookup_multi_ds_2")),
                metadataRegistry(),
                algorithms(),
                properties(),
                SensitivePlaintextAuditRecorder.noOp()
        );

        SensitiveDataContext.SensitiveLookupMeta lookupMeta =
                new SensitiveDataContext.SensitiveLookupMeta("sid_09d8fcbf4d87731eb9ce135bf183957be0b2d5c7f04258dfcaadbe7619d3c755",
                        "pid_12790c679f20d3fef946e2f8b815e3f63f93d5e0dc94a7bea0f8f5d8c0b9fcd7",
                        "U-100",
                        "hash");

        EncryptionException exception = assertThrows(EncryptionException.class, () -> service.lookup(lookupMeta));
        assertEquals("GENERAL_FAILURE", exception.getErrorCode().name());
    }

    @Test
    void shouldLookupPlaintextForSeparateTableField() throws Exception {
        DataSource dataSource = dataSource("lookup_plaintext_separate_table");
        String plainValue = "ID-440300";
        Sm4CipherAlgorithm sm4 = new Sm4CipherAlgorithm("unit-test-key");
        String cipherValue = sm4.encrypt(plainValue);
        String lookupHash = new Sm3AssistedQueryAlgorithm().transform(plainValue);

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("create table user_account (" +
                    "id varchar(64) primary key," +
                    "id_card_ref varchar(255))");
            statement.execute("create table user_id_card_encrypt (" +
                    "id bigint primary key," +
                    "id_card_hash varchar(255)," +
                    "id_card_cipher varchar(255))");
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement mainStatement = connection.prepareStatement(
                     "insert into user_account (id, id_card_ref) values (?, ?)");
             PreparedStatement separateStatement = connection.prepareStatement(
                     "insert into user_id_card_encrypt (id, id_card_hash, id_card_cipher) values (?, ?, ?)")) {
            mainStatement.setString(1, "U-4403");
            mainStatement.setString(2, lookupHash);
            mainStatement.executeUpdate();

            separateStatement.setLong(1, 1L);
            separateStatement.setString(2, lookupHash);
            separateStatement.setString(3, cipherValue);
            separateStatement.executeUpdate();
        }

        DefaultSensitivePlaintextLookupService service = new DefaultSensitivePlaintextLookupService(
                Map.of("dataSource", dataSource),
                separateTableMetadataRegistry(),
                algorithms(),
                separateTableProperties(),
                SensitivePlaintextAuditRecorder.noOp()
        );

        SensitiveDataContext.SensitiveLookupMeta lookupMeta =
                new SensitiveDataContext.SensitiveLookupMeta("sid_user_account",
                        "pid_user_account_idCard",
                        "U-4403",
                        lookupHash);

        assertEquals(plainValue, service.lookup(lookupMeta));
    }

    private DataSource dataSource(String name) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private AlgorithmRegistry algorithms() {
        return new AlgorithmRegistry(
                Map.of("sm4", new Sm4CipherAlgorithm("unit-test-key")),
                Map.of("sm3", new Sm3AssistedQueryAlgorithm()),
                Map.of(
                        "normalizedLike", new NormalizedLikeQueryAlgorithm(),
                        "phoneMaskLike", new PhoneNumberMaskLikeQueryAlgorithm()
                )
        );
    }

    private EncryptMetadataRegistry metadataRegistry() {
        return new EncryptMetadataRegistry(properties(), new AnnotationEncryptMetadataLoader());
    }

    private EncryptMetadataRegistry metadataRegistryWithCustomBusinessColumn() {
        EncryptMetadataRegistry registry =
                new EncryptMetadataRegistry(customBusinessColumnProperties(), new AnnotationEncryptMetadataLoader());
        registry.registerEntityType(CustomBusinessLookupEntity.class);
        return registry;
    }

    private EncryptMetadataRegistry separateTableMetadataRegistry() {
        return new EncryptMetadataRegistry(separateTableProperties(), new AnnotationEncryptMetadataLoader());
    }

    private DatabaseEncryptionProperties properties() {
        DatabaseEncryptionProperties properties = new DatabaseEncryptionProperties();
        DatabaseEncryptionProperties.TableRuleProperties tableRule = new DatabaseEncryptionProperties.TableRuleProperties();
        tableRule.setTable("user_account");

        DatabaseEncryptionProperties.FieldRuleProperties phoneRule = new DatabaseEncryptionProperties.FieldRuleProperties();
        phoneRule.setProperty("phone");
        phoneRule.setColumn("phone");
        phoneRule.setCipherAlgorithm("sm4");
        phoneRule.setAssistedQueryColumn("phone_hash");
        phoneRule.setAssistedQueryAlgorithm("sm3");
        phoneRule.setSidCode("sid_09d8fcbf4d87731eb9ce135bf183957be0b2d5c7f04258dfcaadbe7619d3c755");
        phoneRule.setPidCode("pid_12790c679f20d3fef946e2f8b815e3f63f93d5e0dc94a7bea0f8f5d8c0b9fcd7");
        phoneRule.setLookupBusinessKey("id");

        tableRule.setFields(Collections.singletonList(phoneRule));
        properties.setTables(Collections.singletonList(tableRule));
        return properties;
    }

    private DatabaseEncryptionProperties customBusinessColumnProperties() {
        DatabaseEncryptionProperties properties = new DatabaseEncryptionProperties();
        DatabaseEncryptionProperties.TableRuleProperties tableRule = new DatabaseEncryptionProperties.TableRuleProperties();
        tableRule.setTable("custom_user_account");

        DatabaseEncryptionProperties.FieldRuleProperties phoneRule = new DatabaseEncryptionProperties.FieldRuleProperties();
        phoneRule.setProperty("phone");
        phoneRule.setColumn("phone");
        phoneRule.setCipherAlgorithm("sm4");
        phoneRule.setAssistedQueryColumn("phone_hash");
        phoneRule.setAssistedQueryAlgorithm("sm3");
        phoneRule.setSidCode("sid_custom_user_account");
        phoneRule.setPidCode("pid_custom_user_account_phone");
        phoneRule.setLookupBusinessKey("tenantId");

        tableRule.setFields(Collections.singletonList(phoneRule));
        properties.setTables(Collections.singletonList(tableRule));
        return properties;
    }

    private DatabaseEncryptionProperties separateTableProperties() {
        DatabaseEncryptionProperties properties = new DatabaseEncryptionProperties();
        DatabaseEncryptionProperties.TableRuleProperties tableRule = new DatabaseEncryptionProperties.TableRuleProperties();
        tableRule.setTable("user_account");

        DatabaseEncryptionProperties.FieldRuleProperties idCardRule = new DatabaseEncryptionProperties.FieldRuleProperties();
        idCardRule.setProperty("idCard");
        idCardRule.setColumn("id_card_ref");
        idCardRule.setCipherAlgorithm("sm4");
        idCardRule.setAssistedQueryColumn("id_card_hash");
        idCardRule.setAssistedQueryAlgorithm("sm3");
        idCardRule.setStorageMode(io.github.jasper.mybatis.encrypt.core.metadata.FieldStorageMode.SEPARATE_TABLE);
        idCardRule.setStorageTable("user_id_card_encrypt");
        idCardRule.setStorageColumn("id_card_cipher");
        idCardRule.setStorageIdColumn("id");
        idCardRule.setSidCode("sid_user_account");
        idCardRule.setPidCode("pid_user_account_idCard");
        idCardRule.setLookupBusinessKey("id");

        tableRule.setFields(Collections.singletonList(idCardRule));
        properties.setTables(Collections.singletonList(tableRule));
        return properties;
    }

    @Table(name = "custom_user_account")
    static class CustomBusinessLookupEntity {

        @Column(name = "tenant_user_id")
        private String tenantId;

        private String phone;
    }
}
