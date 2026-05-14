package io.github.jasper.mybatis.encrypt.autoconfigure;

import io.github.jasper.mybatis.encrypt.algorithm.support.IdCardMaskLikeQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.support.PhoneNumberMaskLikeQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import io.github.jasper.mybatis.encrypt.migration.FileMigrationStateStore;
import io.github.jasper.mybatis.encrypt.migration.GlobalMigrationSchemaSqlGeneratorFactory;
import io.github.jasper.mybatis.encrypt.migration.MigrationReport;
import io.github.jasper.mybatis.encrypt.migration.MigrationSchemaSqlGenerator;
import io.github.jasper.mybatis.encrypt.migration.MigrationStateStore;
import io.github.jasper.mybatis.encrypt.migration.MigrationTaskFactory;
import io.github.jasper.mybatis.encrypt.plugin.CompositeWriteParameterPreprocessor;
import io.github.jasper.mybatis.encrypt.plugin.DatabaseEncryptionInterceptor;
import io.github.jasper.mybatis.encrypt.plugin.WriteParameterPreprocessor;
import jakarta.annotation.Resource;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = MybatisEncryptionAutoConfigurationIntegrationTest.TestApplication.class,
        properties = {
                "mybatis.encrypt.enabled=true",
                "mybatis.encrypt.default-cipher-key=boot-integration-key",
                "mybatis.encrypt.scan-entity-annotations=true",
                "mybatis.encrypt.scan-packages=io.github.jasper.mybatis.encrypt.autoconfigure",
                "mybatis.encrypt.migration.checkpoint-directory=target/test-migration-state-spring3",
                "mybatis.encrypt.log-masked-sql=false",
                "mybatis.configuration.map-underscore-to-camel-case=true"
        }
)
@Tag("integration")
@Tag("config")
class MybatisEncryptionAutoConfigurationIntegrationTest {

    @Resource
    private DataSource dataSource;

    @Resource
    private AutoConfiguredUserMapper mapper;

    @Resource
    private EncryptMetadataRegistry metadataRegistry;

    @Resource
    private DatabaseEncryptionInterceptor interceptor;

    @Resource
    private SqlSessionFactory sqlSessionFactory;

    @Resource
    private MigrationTaskFactory migrationTaskFactory;

    @Resource
    private MigrationStateStore migrationStateStore;

    @Resource
    private MigrationSchemaSqlGenerator migrationSchemaSqlGenerator;

    @Resource
    private GlobalMigrationSchemaSqlGeneratorFactory globalMigrationSchemaSqlGeneratorFactory;

    @Resource
    private WriteParameterPreprocessor writeParameterPreprocessor;

    @BeforeEach
    void setUp() throws Exception {
        deleteIfExists(Paths.get("target/test-migration-state-spring3"));
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("drop table if exists user_id_card_encrypt");
            statement.execute("drop table if exists user_account");
            statement.execute("""
                    create table user_account (
                        id bigint primary key,
                        name varchar(64),
                        create_by varchar(64),
                        create_time timestamp,
                        update_by varchar(64),
                        update_time timestamp,
                        sys_org_code varchar(64),
                        tenant_id int,
                        phone varchar(64),
                        phone_backup varchar(64),
                        phone_cipher varchar(512),
                        phone_hash varchar(128),
                        phone_like varchar(255),
                        phone_masked varchar(255),
                        id_card varchar(128),
                        id_card_backup varchar(128)
                    )
                    """);
            statement.execute("""
                    create table user_id_card_encrypt (
                        id bigint primary key,
                        id_card_cipher varchar(512),
                        id_card_hash varchar(128),
                        id_card_like varchar(255),
                        id_card_masked varchar(255),
                        id_card_backup varchar(255)
                    )
                    """);
        }
    }

    @Test
    void shouldAutoConfigurePluginBeansAndEntityScanning() {
        assertNotNull(interceptor);
        assertNotNull(migrationTaskFactory);
        assertNotNull(migrationSchemaSqlGenerator);
        assertNotNull(globalMigrationSchemaSqlGeneratorFactory);
        assertTrue(migrationStateStore instanceof FileMigrationStateStore);
        assertTrue(metadataRegistry.findByEntity(AutoConfiguredUserRecord.class).isPresent());
        assertNotNull(migrationSchemaSqlGenerator.generateForEntity(AutoConfiguredUserRecord.class));
        assertEquals(1, globalMigrationSchemaSqlGeneratorFactory.getDataSourceNames().size());
        assertTrue(globalMigrationSchemaSqlGeneratorFactory.getDataSourceNames().contains("dataSource"));
        assertNotNull(globalMigrationSchemaSqlGeneratorFactory.generateAllRegisteredTables("dataSource"));
        assertEquals(1, sqlSessionFactory.getConfiguration().getInterceptors().stream()
                .filter(DatabaseEncryptionInterceptor.class::isInstance)
                .count());
        assertTrue(writeParameterPreprocessor instanceof CompositeWriteParameterPreprocessor);
    }

    @Test
    void shouldExecuteMigrationThroughAutoConfiguredTaskFactory() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(
                    "insert into user_account (id, name, phone, id_card) values (21, 'Bob', '13900139000', '320101199001011235')");
        }

        MigrationReport report = migrationTaskFactory.executeForEntity(
                AutoConfiguredUserRecord.class,
                "id",
                builder -> builder.backupColumnByColumn("id_card", "id_card_backup"));

        assertEquals(1L, report.getMigratedRows());
        assertEquals("21", report.getLastProcessedCursor());

        AutoConfiguredUserRecord byPhone = mapper.selectByPhone("13900139000");
        assertNotNull(byPhone);
        assertEquals("Bob", byPhone.getName());
        assertEquals("13900139000", byPhone.getPhone());
        assertEquals("320101199001011235", byPhone.getIdCard());

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "select id_card, id_card_backup, phone_cipher, phone_hash from user_account where id = 21")) {
            assertTrue(resultSet.next());
            assertNotEquals("320101199001011235", resultSet.getString("id_card"));
            assertEquals("320101199001011235", resultSet.getString("id_card_backup"));
            assertNotEquals("13900139000", resultSet.getString("phone_cipher"));
            assertNotNull(resultSet.getString("phone_hash"));
        }
    }

    @Test
    void shouldEncryptAndDecryptThroughSpringBootAutoConfiguration() throws Exception {
        AutoConfiguredUserRecord user = new AutoConfiguredUserRecord();
        user.setId(11L);
        user.setName("Alice");
        user.setPhone("13800138000");
        user.setIdCard("320101199001011234");

        assertEquals(1, mapper.insertUser(user));

        AutoConfiguredUserRecord byPhone = mapper.selectByPhone("13800138000");
        assertNotNull(byPhone);
        assertEquals("Alice", byPhone.getName());
        assertEquals("13800138000", byPhone.getPhone());
        assertEquals("320101199001011234", byPhone.getIdCard());

        AutoConfiguredUserRecord byIdCard = mapper.selectByIdCard("320101199001011234");
        assertNotNull(byIdCard);
        assertEquals("Alice", byIdCard.getName());
        assertEquals("320101199001011234", byIdCard.getIdCard());

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet mainResult = statement.executeQuery(
                     "select phone_cipher, phone_hash, phone_like, phone_masked from user_account where id = 11")) {
            assertTrue(mainResult.next());
            assertNotEquals("13800138000", mainResult.getString("phone_cipher"));
            assertNotNull(mainResult.getString("phone_hash"));
            assertEquals("13800138000", mainResult.getString("phone_like"));
            assertEquals(new PhoneNumberMaskLikeQueryAlgorithm().transform("13800138000"),
                    mainResult.getString("phone_masked"));
        }

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet separateResult = statement.executeQuery(
                     "select id, id_card_cipher, id_card_hash, id_card_masked from user_id_card_encrypt")) {
            assertTrue(separateResult.next());
            assertTrue(separateResult.getLong("id") > 0L);
            assertNotEquals("320101199001011234", separateResult.getString("id_card_cipher"));
            assertNotNull(separateResult.getString("id_card_hash"));
            assertEquals(new IdCardMaskLikeQueryAlgorithm().transform("320101199001011234"),
                    separateResult.getString("id_card_masked"));
        }
    }

    @Test
    void shouldApplyJeecgStylePreprocessorBeforeMainTableWriteSqlIsBuilt() throws Exception {
        AutoConfiguredUserRecord user = new AutoConfiguredUserRecord();
        user.setId(31L);
        user.setName("JeecgAlice");
        user.setPhone("13800138031");
        user.setIdCard("320101199001013131");

        assertEquals(1, mapper.insertUser(user));

        assertEquals("jeecg-user", user.getCreateBy());
        assertNotNull(user.getCreateTime());
        assertEquals("ORG-001", user.getSysOrgCode());
        assertEquals(Integer.valueOf(99), user.getTenantId());

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "select create_by, create_time, sys_org_code, tenant_id from user_account where id = 31")) {
            assertTrue(resultSet.next());
            assertEquals("jeecg-user", resultSet.getString("create_by"));
            assertNotNull(resultSet.getTimestamp("create_time"));
            assertEquals("ORG-001", resultSet.getString("sys_org_code"));
            assertEquals(99, resultSet.getInt("tenant_id"));
        }
    }

    @Test
    void shouldApplyCustomWritePreprocessorBeforeRewrite() throws Exception {
        AutoConfiguredUserRecord user = new AutoConfiguredUserRecord();
        user.setId(32L);
        user.setName("JeecgBob");
        user.setPhone("13800138032");
        user.setIdCard("320101199001013232");
        assertEquals(1, mapper.insertUser(user));

        AutoConfiguredUserRecord update = new AutoConfiguredUserRecord();
        update.setId(32L);
        update.setName("JeecgBobUpdated");
        update.setPhone("138****8032");
        update.setIdCard("320101199001013233");

        assertEquals(1, mapper.updateUser(update));

        assertEquals("jeecg-updater", update.getUpdateBy());
        assertNotNull(update.getUpdateTime());
        assertNull(update.getPhone());

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "select phone_hash, phone_masked, update_by, update_time from user_account where id = 32")) {
            assertTrue(resultSet.next());
            assertNotNull(resultSet.getString("phone_hash"));
            assertEquals(new PhoneNumberMaskLikeQueryAlgorithm().transform("13800138032"),
                    resultSet.getString("phone_masked"));
            assertEquals("jeecg-updater", resultSet.getString("update_by"));
            assertNotNull(resultSet.getTimestamp("update_time"));
        }

        AutoConfiguredUserRecord refreshed = mapper.selectByPhone("13800138032");
        assertNotNull(refreshed);
        assertEquals("JeecgBobUpdated", refreshed.getName());
        assertEquals("13800138032", refreshed.getPhone());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @MapperScan(basePackageClasses = AutoConfiguredUserMapper.class)
    static class TestApplication {

        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:boot_encrypt_test;MODE=MySQL;DB_CLOSE_DELAY=-1");
            dataSource.setUser("sa");
            dataSource.setPassword("");
            return dataSource;
        }

        @Bean(name = "mybatisInterceptor")
        Interceptor mybatisInterceptor() {
            return new org.jeecg.config.mybatis.MybatisInterceptor();
        }

        @Bean
        WriteParameterPreprocessor maskedPhoneClearingPreprocessor() {
            return new WriteParameterPreprocessor() {
                @Override
                public void preprocess(MappedStatement mappedStatement, Object parameterObject) {
                    if (parameterObject == null) {
                        return;
                    }
                    String commandType = mappedStatement == null || mappedStatement.getSqlCommandType() == null
                            ? null : mappedStatement.getSqlCommandType().name();
                    if (!"UPDATE".equals(commandType)) {
                        return;
                    }
                    try {
                        Field phoneField = findField(parameterObject.getClass(), "phone");
                        if (phoneField == null) {
                            return;
                        }
                        phoneField.setAccessible(true);
                        Object phone = phoneField.get(parameterObject);
                        if (phone instanceof String && ((String) phone).contains("***")) {
                            phoneField.set(parameterObject, null);
                        }
                        phoneField.setAccessible(false);
                    } catch (IllegalAccessException ex) {
                        throw new IllegalStateException("Failed to clear masked phone value before SQL rewrite", ex);
                    }
                }
            };
        }
    }

    private static Field findField(Class<?> type, String fieldName) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignore) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private void deleteIfExists(Path directory) throws Exception {
        if (!Files.exists(directory)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ex) {
                            throw new RuntimeException(ex);
                        }
                    });
        }
    }
}
