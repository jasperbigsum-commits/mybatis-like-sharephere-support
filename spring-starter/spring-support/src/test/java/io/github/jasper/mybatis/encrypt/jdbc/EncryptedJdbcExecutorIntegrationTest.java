package io.github.jasper.mybatis.encrypt.jdbc;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.algorithm.support.NormalizedLikeQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.support.Sm3AssistedQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.support.Sm4CipherAlgorithm;
import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.config.EncryptedJdbcAutoConfiguration;
import io.github.jasper.mybatis.encrypt.core.decrypt.ResultDecryptor;
import io.github.jasper.mybatis.encrypt.core.metadata.AnnotationEncryptMetadataLoader;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import io.github.jasper.mybatis.encrypt.core.rewrite.SqlRewriteEngine;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.StringReader;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Tag("integration")
@Tag("jdbc")
class EncryptedJdbcExecutorIntegrationTest {

    private static final String DB_URL = "jdbc:h2:mem:encrypted_jdbc_test;MODE=MYSQL;DB_CLOSE_DELAY=-1";
    private static final String PHONE = "13800138000";
    private static final String PHONE_CIPHER = new Sm4CipherAlgorithm("change-me-before-production").encrypt(PHONE);
    private static final String PHONE_HASH = new Sm3AssistedQueryAlgorithm("").transform(PHONE);

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(EncryptedJdbcAutoConfiguration.class))
            .withBean(DataSource.class, EncryptedJdbcExecutorIntegrationTest::dataSource)
            .withBean(DatabaseEncryptionProperties.class, EncryptedJdbcExecutorIntegrationTest::properties)
            .withBean(AnnotationEncryptMetadataLoader.class, AnnotationEncryptMetadataLoader::new)
            .withBean(EncryptMetadataRegistry.class,
                    () -> new EncryptMetadataRegistry(properties(), new AnnotationEncryptMetadataLoader()))
            .withBean(AlgorithmRegistry.class, () -> new AlgorithmRegistry(
                    Collections.singletonMap("sm4", new Sm4CipherAlgorithm("change-me-before-production")),
                    Collections.singletonMap("sm3", new Sm3AssistedQueryAlgorithm("")),
                    Collections.singletonMap("normalizedLike", new NormalizedLikeQueryAlgorithm())))
            .withBean(SqlRewriteEngine.class, () -> new SqlRewriteEngine(
                    new EncryptMetadataRegistry(properties(), new AnnotationEncryptMetadataLoader()),
                    new AlgorithmRegistry(
                            Collections.singletonMap("sm4", new Sm4CipherAlgorithm("change-me-before-production")),
                            Collections.singletonMap("sm3", new Sm3AssistedQueryAlgorithm("")),
                            Collections.singletonMap("normalizedLike", new NormalizedLikeQueryAlgorithm())),
                    properties()))
            .withBean(ResultDecryptor.class, () -> new ResultDecryptor(
                    new EncryptMetadataRegistry(properties(), new AnnotationEncryptMetadataLoader()),
                    new AlgorithmRegistry(
                            Collections.singletonMap("sm4", new Sm4CipherAlgorithm("change-me-before-production")),
                            Collections.singletonMap("sm3", new Sm3AssistedQueryAlgorithm("")),
                            Collections.singletonMap("normalizedLike", new NormalizedLikeQueryAlgorithm())),
                    null));

    @BeforeEach
    void initSchema() {
        try (java.sql.Connection connection = dataSource().getConnection()) {
            RunScript.execute(connection, new StringReader(
                    "drop table if exists user_account;" +
                            "create table user_account (" +
                            "id bigint primary key," +
                            "phone varchar(255) not null," +
                            "phone_hash varchar(255) not null," +
                            "name varchar(255) not null" +
                            ");" +
                            "insert into user_account (id, phone, phone_hash, name) values " +
                            "(1, '" + PHONE_CIPHER + "', '" + PHONE_HASH + "', 'alice');"));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void shouldExposeEncryptedJdbcExecutorBean() {
        contextRunner.run(context -> assertNotNull(context.getBean(EncryptedJdbcExecutor.class)));
    }

    @Test
    void shouldNotExposeEncryptedJdbcExecutorBeanWhenEncryptionDisabled() {
        contextRunner
                .withPropertyValues("mybatis.encrypt.enabled=false")
                .run(context -> assertFalse(context.containsBean("encryptedJdbcExecutor")));
    }

    @Test
    void shouldRewriteEncryptedPredicateAndDecryptSelectedRows() {
        contextRunner.run(context -> {
            EncryptedJdbcExecutor executor = context.getBean(EncryptedJdbcExecutor.class);
            List<Map<String, Object>> rows = executor.select(
                    null,
                    "select id, phone, name from user_account where phone = ?",
                    PHONE);
            assertEquals(1, rows.size());
            assertEquals("alice", readIgnoreCase(rows.get(0), "name"));
            assertEquals(PHONE, readIgnoreCase(rows.get(0), "phone"));
        });
    }

    private static DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl(DB_URL);
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private static DatabaseEncryptionProperties properties() {
        DatabaseEncryptionProperties properties = new DatabaseEncryptionProperties();
        DatabaseEncryptionProperties.TableRuleProperties tableRule = new DatabaseEncryptionProperties.TableRuleProperties();
        tableRule.setTable("user_account");

        DatabaseEncryptionProperties.FieldRuleProperties phoneRule = new DatabaseEncryptionProperties.FieldRuleProperties();
        phoneRule.setProperty("phone");
        phoneRule.setColumn("phone");
        phoneRule.setCipherAlgorithm("sm4");
        phoneRule.setAssistedQueryColumn("phone_hash");
        phoneRule.setAssistedQueryAlgorithm("sm3");

        tableRule.setFields(Collections.singletonList(phoneRule));
        properties.setTables(Collections.singletonList(tableRule));
        return properties;
    }

    private static Object readIgnoreCase(Map<String, Object> row, String key) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
