package io.github.jasper.mybatis.encrypt.autoconfigure;

import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import io.github.jasper.mybatis.encrypt.plugin.DatabaseEncryptionInterceptor;
import jakarta.annotation.Resource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        classes = MybatisEncryptionAutoConfigurationIntegrationTest.TestApplication.class,
        properties = {
                "mybatis.encrypt.enabled=true",
                "mybatis.encrypt.default-cipher-key=boot-integration-key",
                "mybatis.encrypt.scan-entity-annotations=true",
                "mybatis.encrypt.scan-packages=io.github.jasper.mybatis.encrypt.autoconfigure",
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

    @BeforeEach
    void setUp() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("drop table if exists user_id_card_encrypt");
            statement.execute("drop table if exists user_account");
            statement.execute("""
                    create table user_account (
                        id bigint primary key,
                        name varchar(64),
                        phone_cipher varchar(512),
                        phone_hash varchar(128),
                        phone_like varchar(255),
                        id_card bigint
                    )
                    """);
            statement.execute("""
                    create table user_id_card_encrypt (
                        id bigint auto_increment primary key,
                        id_card_cipher varchar(512),
                        id_card_hash varchar(128),
                        id_card_like varchar(255)
                    )
                    """);
        }
    }

    @Test
    void shouldAutoConfigurePluginBeansAndEntityScanning() {
        assertNotNull(interceptor);
        assertTrue(metadataRegistry.findByEntity(AutoConfiguredUserRecord.class).isPresent());
        assertEquals(1, sqlSessionFactory.getConfiguration().getInterceptors().stream()
                .filter(DatabaseEncryptionInterceptor.class::isInstance)
                .count());
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
            mainResult.next();
            assertNotEquals("13800138000", mainResult.getString("phone_cipher"));
            assertNotNull(mainResult.getString("phone_hash"));
            assertEquals("13800138000", mainResult.getString("phone_like"));
        }

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet separateResult = statement.executeQuery(
                     "select id, id_card_cipher, id_card_hash from user_id_card_encrypt")) {
            separateResult.next();
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
}
