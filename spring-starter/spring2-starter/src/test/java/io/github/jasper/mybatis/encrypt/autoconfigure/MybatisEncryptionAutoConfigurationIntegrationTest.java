package io.github.jasper.mybatis.encrypt.autoconfigure;

import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import io.github.jasper.mybatis.encrypt.migration.FileMigrationStateStore;
import io.github.jasper.mybatis.encrypt.migration.MigrationReport;
import io.github.jasper.mybatis.encrypt.migration.MigrationStateStore;
import io.github.jasper.mybatis.encrypt.migration.MigrationTaskFactory;
import io.github.jasper.mybatis.encrypt.plugin.DatabaseEncryptionInterceptor;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.Resource;
import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = MybatisEncryptionAutoConfigurationIntegrationTest.TestApplication.class,
        properties = {
                "mybatis.encrypt.enabled=true",
                "mybatis.encrypt.default-cipher-key=boot-integration-key",
                "mybatis.encrypt.scan-entity-annotations=true",
                "mybatis.encrypt.scan-packages=io.github.jasper.mybatis.encrypt.autoconfigure",
                "mybatis.encrypt.migration.checkpoint-directory=target/test-migration-state-spring2",
                "mybatis.encrypt.log-masked-sql=false",
                "mybatis.configuration.map-underscore-to-camel-case=true"
        }
)
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

    @BeforeEach
    void setUp() throws Exception {
        deleteIfExists(Paths.get("target/test-migration-state-spring2"));
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("drop table if exists user_id_card_encrypt");
            statement.execute("drop table if exists user_account");
            statement.execute("create table user_account (" +
                    "id bigint primary key," +
                    "name varchar(64)," +
                    "phone varchar(64)," +
                    "phone_backup varchar(64)," +
                    "phone_cipher varchar(512)," +
                    "phone_hash varchar(128)," +
                    "phone_like varchar(255)," +
                    "id_card varchar(128)," +
                    "id_card_backup varchar(128))");
            statement.execute("create table user_id_card_encrypt (" +
                    "id bigint primary key," +
                    "id_card_cipher varchar(512)," +
                    "id_card_hash varchar(128)," +
                    "id_card_like varchar(255))");
        }
    }

    @Test
    void shouldAutoConfigurePluginBeansAndEntityScanning() {
        assertNotNull(interceptor);
        assertNotNull(migrationTaskFactory);
        assertTrue(migrationStateStore instanceof FileMigrationStateStore);
        assertTrue(metadataRegistry.findByEntity(AutoConfiguredUserRecord.class).isPresent());
        assertEquals(1L, sqlSessionFactory.getConfiguration().getInterceptors().stream()
                .filter(DatabaseEncryptionInterceptor.class::isInstance)
                .count());
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
                     "select phone_cipher, phone_hash, phone_like from user_account where id = 11")) {
            assertTrue(mainResult.next());
            assertNotEquals("13800138000", mainResult.getString("phone_cipher"));
            assertNotNull(mainResult.getString("phone_hash"));
            assertEquals("13800138000", mainResult.getString("phone_like"));
        }

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet separateResult = statement.executeQuery(
                     "select id, id_card_cipher, id_card_hash from user_id_card_encrypt")) {
            assertTrue(separateResult.next());
            assertTrue(separateResult.getLong("id") > 0L);
            assertNotEquals("320101199001011234", separateResult.getString("id_card_cipher"));
            assertNotNull(separateResult.getString("id_card_hash"));
        }
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
