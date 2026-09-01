package io.github.jasper.mybatis.encrypt.core.mask;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.algorithm.support.NormalizedLikeQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.support.PhoneNumberMaskLikeQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.support.Sm3AssistedQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.support.Sm4CipherAlgorithm;
import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.config.SqlDialectContextHolder;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import io.github.jasper.mybatis.encrypt.core.metadata.FieldStorageMode;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Collection;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
@Tag("mask")
class JdbcStoredSensitiveValueResolverTest {

    /**
     * 测试目的：验证敏感响应脱敏能按记录值、存储态脱敏列或自定义脱敏器选择正确来源。
     * 测试场景：构造控制器作用域、敏感字段注解和数据库脱敏值，断言响应写出前的脱敏结果和作用域释放顺序。
     */
    @Test
    void shouldResolveStoredMaskedValueFromDatabase() throws Exception {
        AlgorithmRegistry algorithms = algorithms();
        DataSource dataSource = dataSource("stored_masked_value");
        String plainValue = "13800138000";
        String lookupValue = algorithms.assisted("sm3").transform(plainValue);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("create table user_account (phone_hash varchar(128), phone_masked varchar(64))");
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "insert into user_account (phone_hash, phone_masked) values (?, ?)")) {
            statement.setString(1, lookupValue);
            statement.setString(2, "138****8000");
            statement.executeUpdate();
        }

        EncryptColumnRule rule = new EncryptColumnRule(
                "phone",
                "user_account",
                "phone",
                "sm4",
                "phone_hash",
                "sm3",
                "phone_like",
                "normalizedLike",
                "phone_masked",
                "phoneMaskLike",
                FieldStorageMode.SAME_TABLE,
                null,
                "phone_cipher",
                null
        );
        JdbcStoredSensitiveValueResolver resolver = new JdbcStoredSensitiveValueResolver(
                Map.of("dataSource", dataSource),
                algorithms,
                new DatabaseEncryptionProperties()
        );
        DemoDto dto = new DemoDto(plainValue);

        try (SensitiveDataContext.Scope ignored = SensitiveDataContext.open(false, SensitiveResponseStrategy.RECORDED_ONLY);
             SqlDialectContextHolder.Scope ignoredDataSource = SqlDialectContextHolder.open("dataSource")) {
            SensitiveDataContext.record(dto, "phone", plainValue, rule);
            Collection<SensitiveDataContext.SensitiveRecord> records = SensitiveDataContext.records();

            Map<SensitiveDataContext.SensitiveRecord, String> resolved = resolver.resolve(records);

            assertEquals("138****8000", resolved.get(records.iterator().next()));
        }
    }

    /**
     * 测试目的：验证敏感响应脱敏能按记录值、存储态脱敏列或自定义脱敏器选择正确来源。
     * 测试场景：构造控制器作用域、敏感字段注解和数据库脱敏值，断言响应写出前的脱敏结果和作用域释放顺序。
     */
    @Test
    void shouldResolveSharedLikeMaskedColumnFromDatabase() throws Exception {
        AlgorithmRegistry algorithms = algorithms();
        DataSource dataSource = dataSource("shared_like_masked_value");
        String plainValue = "13800138000";
        String lookupValue = algorithms.assisted("sm3").transform(plainValue);
        String storedMaskedValue = algorithms.like("phoneMaskLike").transform(plainValue);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("create table user_account (phone_hash varchar(128), phone_like varchar(64))");
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "insert into user_account (phone_hash, phone_like) values (?, ?)")) {
            statement.setString(1, lookupValue);
            statement.setString(2, storedMaskedValue);
            statement.executeUpdate();
        }

        EncryptColumnRule rule = new EncryptColumnRule(
                "phone",
                "user_account",
                "phone",
                "sm4",
                "phone_hash",
                "sm3",
                "phone_like",
                "phoneMaskLike",
                "phone_like",
                "phoneMaskLike",
                FieldStorageMode.SAME_TABLE,
                null,
                "phone_cipher",
                null
        );
        JdbcStoredSensitiveValueResolver resolver = new JdbcStoredSensitiveValueResolver(
                Map.of("dataSource", dataSource),
                algorithms,
                new DatabaseEncryptionProperties()
        );
        DemoDto dto = new DemoDto(plainValue);

        try (SensitiveDataContext.Scope ignored = SensitiveDataContext.open(false, SensitiveResponseStrategy.RECORDED_ONLY);
             SqlDialectContextHolder.Scope ignoredDataSource = SqlDialectContextHolder.open("dataSource")) {
            SensitiveDataContext.record(dto, "phone", plainValue, rule);
            Collection<SensitiveDataContext.SensitiveRecord> records = SensitiveDataContext.records();

            Map<SensitiveDataContext.SensitiveRecord, String> resolved = resolver.resolve(records);

            assertEquals(storedMaskedValue, resolved.get(records.iterator().next()));
        }
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

    static class DemoDto {

        private final String phone;

        DemoDto(String phone) {
            this.phone = phone;
        }
    }
}
