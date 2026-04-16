package io.github.jasper.mybatis.encrypt.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SqlDialectTest {

    @Test
    void shouldQuoteMysqlIdentifiers() {
        assertEquals("`user_account`", SqlDialect.MYSQL.quote("user_account"));
        assertEquals("`phone_hash`", SqlDialect.OCEANBASE.quote("phone_hash"));
    }

    @Test
    void shouldQuoteDmIdentifiers() {
        assertEquals("\"USER_ACCOUNT\"", SqlDialect.DM.quote("USER_ACCOUNT"));
    }

    @Test
    void shouldQuoteOracle12Identifiers() {
        assertEquals("\"user_account\"", SqlDialect.ORACLE12.quote("user_account"));
    }

    @Test
    void shouldQuoteClickHouseIdentifiers() {
        assertEquals("`user_account`", SqlDialect.CLICKHOUSE.quote("user_account"));
    }

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
