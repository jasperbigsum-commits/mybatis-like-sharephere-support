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
}
