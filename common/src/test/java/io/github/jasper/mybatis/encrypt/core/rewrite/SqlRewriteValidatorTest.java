package io.github.jasper.mybatis.encrypt.core.rewrite;

import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptTableRule;
import io.github.jasper.mybatis.encrypt.core.metadata.FieldStorageMode;
import io.github.jasper.mybatis.encrypt.exception.EncryptionErrorCode;
import io.github.jasper.mybatis.encrypt.exception.UnsupportedEncryptedOperationException;
import io.github.jasper.mybatis.encrypt.util.JSqlParserSupport;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
@Tag("rewrite")
class SqlRewriteValidatorTest {

    private final SqlRewriteValidator validator = new SqlRewriteValidator();

    @Test
    void shouldRejectOrderByOnEncryptedField() throws Exception {
        PlainSelect plainSelect = parsePlainSelect("SELECT phone FROM user_account ORDER BY phone");

        UnsupportedEncryptedOperationException exception = assertThrows(
                UnsupportedEncryptedOperationException.class,
                () -> validator.validateSelect(plainSelect, tableContext())
        );

        assertEquals(EncryptionErrorCode.UNSUPPORTED_ENCRYPTED_ORDER_BY, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("ORDER BY"));
    }

    @Test
    void shouldRejectAggregateOnEncryptedField() throws Exception {
        PlainSelect plainSelect = parsePlainSelect("SELECT MAX(phone) FROM user_account");

        UnsupportedEncryptedOperationException exception = assertThrows(
                UnsupportedEncryptedOperationException.class,
                () -> validator.validateSelect(plainSelect, tableContext())
        );

        assertEquals(EncryptionErrorCode.UNSUPPORTED_ENCRYPTED_AGGREGATION, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("Aggregate"));
    }

    @Test
    void shouldAllowPlainOrderByWhenEncryptedColumnIsNotReferenced() throws Exception {
        PlainSelect plainSelect = parsePlainSelect("SELECT name FROM user_account ORDER BY name");

        assertDoesNotThrow(() -> validator.validateSelect(plainSelect, tableContext()));
    }

    private PlainSelect parsePlainSelect(String sql) throws Exception {
        Statement statement = JSqlParserSupport.parseStatement(sql);
        Select select = (Select) statement;
        return (PlainSelect) select;
    }

    private SqlTableContext tableContext() {
        EncryptTableRule tableRule = new EncryptTableRule("user_account");
        tableRule.addColumnRule(new EncryptColumnRule(
                "phone",
                "user_account",
                "phone",
                "sm4",
                "phone_hash",
                "sm3",
                "phone_like",
                "like",
                FieldStorageMode.SAME_TABLE,
                null,
                "phone_cipher",
                null
        ));
        SqlTableContext tableContext = new SqlTableContext();
        tableContext.register("user_account", null, tableRule);
        return tableContext;
    }
}
