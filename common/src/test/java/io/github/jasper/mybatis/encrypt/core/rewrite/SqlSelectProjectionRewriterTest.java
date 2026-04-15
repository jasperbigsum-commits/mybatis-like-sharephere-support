package io.github.jasper.mybatis.encrypt.core.rewrite;

import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptTableRule;
import io.github.jasper.mybatis.encrypt.core.metadata.FieldStorageMode;
import io.github.jasper.mybatis.encrypt.exception.EncryptionErrorCode;
import io.github.jasper.mybatis.encrypt.exception.UnsupportedEncryptedOperationException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlSelectProjectionRewriterTest {

    private final SqlSelectProjectionRewriter rewriter = new SqlSelectProjectionRewriter(
            this::resolveEncryptedColumn,
            this::buildColumn,
            this::requireAssistedQueryColumn
    );

    @Test
    void shouldRewriteEncryptedProjectionToStorageAlias() throws Exception {
        PlainSelect plainSelect = parsePlainSelect("SELECT phone FROM user_account");

        boolean changed = rewriter.rewrite(plainSelect, tableContext(), ProjectionMode.NORMAL);

        assertTrue(changed);
        assertTrue(plainSelect.toString().contains("phone_cipher AS phone")
                || plainSelect.toString().contains("phone_cipher phone"));
    }

    @Test
    void shouldPrependEncryptedAliasBeforeWildcard() throws Exception {
        PlainSelect plainSelect = parsePlainSelect("SELECT * FROM user_account");

        boolean changed = rewriter.rewrite(plainSelect, tableContext(), ProjectionMode.NORMAL);

        assertTrue(changed);
        assertTrue(plainSelect.toString().contains("phone_cipher AS phone, *")
                || plainSelect.toString().contains("phone_cipher phone, *"));
    }

    @Test
    void shouldAppendDerivedHelperColumnsForDerivedProjection() throws Exception {
        PlainSelect plainSelect = parsePlainSelect("SELECT phone FROM user_account");

        boolean changed = rewriter.rewrite(plainSelect, tableContext(), ProjectionMode.DERIVED);

        assertTrue(changed);
        assertTrue(plainSelect.toString().contains("__enc_assisted_phone"));
        assertTrue(plainSelect.toString().contains("__enc_like_phone"));
    }

    @Test
    void shouldRejectWildcardInComparisonSubqueryProjection() throws Exception {
        PlainSelect plainSelect = parsePlainSelect("SELECT * FROM user_account");

        UnsupportedEncryptedOperationException exception = assertThrows(
                UnsupportedEncryptedOperationException.class,
                () -> rewriter.rewrite(plainSelect, tableContext(), ProjectionMode.COMPARISON)
        );

        assertEquals(EncryptionErrorCode.UNSUPPORTED_ENCRYPTED_IN_QUERY, exception.getErrorCode());
    }

    private PlainSelect parsePlainSelect(String sql) throws Exception {
        Statement statement = CCJSqlParserUtil.parse(sql);
        Select select = (Select) statement;
        return (PlainSelect) select;
    }

    private SqlTableContext tableContext() {
        EncryptTableRule tableRule = new EncryptTableRule("user_account");
        tableRule.addColumnRule(phoneRule());
        SqlTableContext tableContext = new SqlTableContext();
        tableContext.register("user_account", null, tableRule);
        return tableContext;
    }

    private EncryptColumnRule phoneRule() {
        return new EncryptColumnRule(
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
        );
    }

    private ColumnResolution resolveEncryptedColumn(Expression expression, SqlTableContext tableContext) {
        if (!(expression instanceof Column)) {
            return null;
        }
        Column column = (Column) expression;
        EncryptColumnRule rule = tableContext.resolve(column).orElse(null);
        return rule == null ? null : new ColumnResolution(column, rule, true);
    }

    private Column buildColumn(Column source, String targetColumn) {
        Column column = new Column(targetColumn);
        if (source.getTable() != null && source.getTable().getName() != null) {
            column.setTable(new Table(source.getTable().getName()));
        }
        return column;
    }

    private String requireAssistedQueryColumn(EncryptColumnRule rule, String scenario) {
        if (!rule.hasAssistedQueryColumn()) {
            throw new UnsupportedEncryptedOperationException(EncryptionErrorCode.MISSING_ASSISTED_QUERY_COLUMN,
                    "Encrypted " + scenario + " requires assistedQueryColumn.");
        }
        return rule.assistedQueryColumn();
    }
}
