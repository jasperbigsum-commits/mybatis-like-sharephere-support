package io.github.jasper.mybatis.encrypt.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
@Tag("config")
class SqlDialectTest {

    /**
     * 测试目的：验证 SQL 方言能按数据源或显式配置正确处理标识符引用规则。
     * 测试场景：构造 MySQL、达梦、Oracle、ClickHouse 等方言输入，断言表名和列名引用结果正确。
     */
    @Test
    void shouldQuoteMysqlIdentifiers() {
        assertEquals("`user_account`", SqlDialect.MYSQL.quote("user_account"));
        assertEquals("`phone_hash`", SqlDialect.OCEANBASE.quote("phone_hash"));
    }

    /**
     * 测试目的：验证 SQL 方言能按数据源或显式配置正确处理标识符引用规则。
     * 测试场景：构造 MySQL、达梦、Oracle、ClickHouse 等方言输入，断言表名和列名引用结果正确。
     */
    @Test
    void shouldQuoteDmIdentifiers() {
        assertEquals("\"USER_ACCOUNT\"", SqlDialect.DM.quote("USER_ACCOUNT"));
    }

    /**
     * 测试目的：验证 SQL 方言能按数据源或显式配置正确处理标识符引用规则。
     * 测试场景：构造 MySQL、达梦、Oracle、ClickHouse 等方言输入，断言表名和列名引用结果正确。
     */
    @Test
    void shouldQuoteOracle12Identifiers() {
        assertEquals("\"user_account\"", SqlDialect.ORACLE12.quote("user_account"));
    }

    /**
     * 测试目的：验证 SQL 方言能按数据源或显式配置正确处理标识符引用规则。
     * 测试场景：构造 MySQL、达梦、Oracle、ClickHouse 等方言输入，断言表名和列名引用结果正确。
     */
    @Test
    void shouldQuoteClickHouseIdentifiers() {
        assertEquals("`user_account`", SqlDialect.CLICKHOUSE.quote("user_account"));
    }

    /**
     * 测试目的：验证 SQL 方言能按数据源或显式配置正确处理标识符引用规则。
     * 测试场景：构造 MySQL、达梦、Oracle、ClickHouse 等方言输入，断言表名和列名引用结果正确。
     */
    @Test
    void shouldResolveDialectByDatasourcePipePattern() {
        DatabaseEncryptionProperties properties = new DatabaseEncryptionProperties();
        DatabaseEncryptionProperties.DataSourceDialectRuleProperties rule =
                new DatabaseEncryptionProperties.DataSourceDialectRuleProperties();
        rule.setDatasourceNamePattern("mysql-main|mysql-slave-*");
        rule.setSqlDialect(SqlDialect.MYSQL);
        DatabaseEncryptionProperties.DataSourceDialectRuleProperties oracleRule =
                new DatabaseEncryptionProperties.DataSourceDialectRuleProperties();
        oracleRule.setDatasourceNamePattern("oracle-report");
        oracleRule.setSqlDialect(SqlDialect.ORACLE12);
        properties.getDatasourceDialects().add(rule);
        properties.getDatasourceDialects().add(oracleRule);

        assertEquals(SqlDialect.MYSQL, properties.resolveSqlDialect("mysql-slave-read"));
        assertEquals(SqlDialect.ORACLE12, properties.resolveSqlDialect("oracle-report"));
    }

    /**
     * 测试目的：验证 SQL 方言能按数据源或显式配置正确处理标识符引用规则。
     * 测试场景：构造 MySQL、达梦、Oracle、ClickHouse 等方言输入，断言表名和列名引用结果正确。
     */
    @Test
    void shouldEscapeInternalQuoteCharacters() {
        assertEquals("`na``me`", SqlDialect.MYSQL.quote("na`me"));
        assertEquals("\"na\"\"me\"", SqlDialect.DM.quote("na\"me"));
        assertEquals("`a``b``c`", SqlDialect.MYSQL.quote("a`b`c"));
    }

    @Test
    void shouldStripAndReEscapeAlreadyQuotedIdentifier() {
        assertEquals("`phone`", SqlDialect.MYSQL.quote("`phone`"));
        assertEquals("`na``me`", SqlDialect.MYSQL.quote("`na`me`"));
        assertEquals("\"na\"\"me\"", SqlDialect.ORACLE12.quote("\"na\"me\""));
    }

    @Test
    void shouldHandleBlankIdentifier() {
        assertEquals("", SqlDialect.MYSQL.quote(""));
    }

    @Test
    void shouldUseContextDatasourceDialectWhenReadingGlobalDialect() {
        DatabaseEncryptionProperties properties = new DatabaseEncryptionProperties();
        properties.setSqlDialect(SqlDialect.DM);
        DatabaseEncryptionProperties.DataSourceDialectRuleProperties rule =
                new DatabaseEncryptionProperties.DataSourceDialectRuleProperties();
        rule.setDatasourceNamePattern("clickhouse-*");
        rule.setSqlDialect(SqlDialect.CLICKHOUSE);
        properties.getDatasourceDialects().add(rule);

        try (SqlDialectContextHolder.Scope ignored = SqlDialectContextHolder.open("clickhouse-analytics")) {
            assertEquals(SqlDialect.CLICKHOUSE, properties.getSqlDialect());
        }
        assertEquals(SqlDialect.DM, properties.getSqlDialect());
    }
}
