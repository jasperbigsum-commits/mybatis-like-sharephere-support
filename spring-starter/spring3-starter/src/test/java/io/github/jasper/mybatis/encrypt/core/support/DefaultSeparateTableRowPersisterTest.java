package io.github.jasper.mybatis.encrypt.core.support;

import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.exception.EncryptionConfigurationException;
import io.github.jasper.mybatis.encrypt.exception.EncryptionErrorCode;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
@Tag("support")
class DefaultSeparateTableRowPersisterTest {

    private JdbcDataSource dataSource;
    private DefaultSeparateTableRowPersister persister;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:separate_table_persister_test;MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        DatabaseEncryptionProperties properties = new DatabaseEncryptionProperties();
        persister = new DefaultSeparateTableRowPersister(dataSource, properties);

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("drop table if exists user_id_card_encrypt");
            statement.execute("create table user_id_card_encrypt (" +
                    "id bigint primary key," +
                    "id_card_cipher varchar(512)," +
                    "id_card_hash varchar(128)," +
                    "id_card_like varchar(255))");
        }
    }

    /**
     * 测试目的：验证独立表写入请求为空或非法时会被安全拒绝。
     * 测试场景：构造缺少目标表或列值的插入请求，断言不会执行无意义 JDBC 写入。
     */
    @Test
    void shouldRejectEmptyInsertRequest() {
        EncryptionConfigurationException exception = assertThrows(
                EncryptionConfigurationException.class,
                () -> persister.insert(new SeparateTableInsertRequest("user_id_card_encrypt", Map.of()), null, null)
        );

        assertEquals(EncryptionErrorCode.SEPARATE_TABLE_OPERATION_FAILED, exception.getErrorCode());
        assertEquals("Separate-table insert request must not be empty.", exception.getMessage());
    }

    /**
     * 测试目的：验证独立表支撑组件能正确执行 JDBC 兜底写入和托管语句识别。
     * 测试场景：构造独立表列值和 StatementId，断言插入参数、SQL 生成和托管前缀判断符合预期。
     */
    @Test
    void shouldInsertSeparateTableRowViaJdbcFallback() throws Exception {
        LinkedHashMap<String, Object> columnValues = new LinkedHashMap<>();
        columnValues.put("id", 11L);
        columnValues.put("id_card_cipher", "cipher-text");
        columnValues.put("id_card_hash", "hash-value");
        columnValues.put("id_card_like", "like-value");

        persister.insert(new SeparateTableInsertRequest("user_id_card_encrypt", columnValues), null, null);

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "select id, id_card_cipher, id_card_hash, id_card_like from user_id_card_encrypt where id = 11")) {
            assertTrue(resultSet.next());
            assertEquals(11L, resultSet.getLong("id"));
            assertEquals("cipher-text", resultSet.getString("id_card_cipher"));
            assertEquals("hash-value", resultSet.getString("id_card_hash"));
            assertEquals("like-value", resultSet.getString("id_card_like"));
        }
    }

    /**
     * 测试目的：验证独立表支撑组件能正确执行 JDBC 兜底写入和托管语句识别。
     * 测试场景：构造独立表列值和 StatementId，断言插入参数、SQL 生成和托管前缀判断符合预期。
     */
    @Test
    void shouldRecognizeManagedStatementIdPrefix() {
        assertTrue(DefaultSeparateTableRowPersister.isManagedStatementId(
                DefaultSeparateTableRowPersister.class.getName() + ".insert.user_id_card_encrypt.abc"));
        assertFalse(DefaultSeparateTableRowPersister.isManagedStatementId("custom.statement.id"));
    }
}
