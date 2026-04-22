package io.github.jasper.mybatis.encrypt.core.rewrite;

import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptTableRule;
import io.github.jasper.mybatis.encrypt.core.metadata.FieldStorageMode;
import io.github.jasper.mybatis.encrypt.exception.EncryptionErrorCode;
import io.github.jasper.mybatis.encrypt.exception.UnsupportedEncryptedOperationException;
import io.github.jasper.mybatis.encrypt.util.JSqlParserSupport;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
@Tag("rewrite")
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
        assertTrue(plainSelect.toString().contains("phone_cipher AS phone, user_account.*")
                || plainSelect.toString().contains("phone_cipher phone, user_account.*"));
    }

    @Test
    void shouldKeepWildcardWhenStorageColumnEqualsLogicalColumn() throws Exception {
        PlainSelect plainSelect = parsePlainSelect("SELECT * FROM user_account");

        boolean changed = rewriter.rewrite(plainSelect, sameColumnStorageTableContext(), ProjectionMode.NORMAL);

        assertFalse(changed);
        assertEquals("SELECT * FROM user_account", plainSelect.toString());
    }

    @Test
    void shouldNotDuplicateEncryptedProjectionWhenExplicitColumnAppearsBeforeWildcard() throws Exception {
        PlainSelect plainSelect = parsePlainSelect("SELECT phone, * FROM user_account");

        boolean changed = rewriter.rewrite(plainSelect, tableContext(), ProjectionMode.NORMAL);

        assertTrue(changed);
        assertEquals(1, countOccurrences(plainSelect.toString(), "phone_cipher"));
        assertTrue(plainSelect.toString().contains("phone_cipher AS phone, user_account.*")
                || plainSelect.toString().contains("phone_cipher phone, user_account.*"));
    }

    @Test
    void shouldNotDuplicateEncryptedProjectionWhenExplicitColumnAppearsAfterWildcard() throws Exception {
        PlainSelect plainSelect = parsePlainSelect("SELECT *, phone FROM user_account");

        boolean changed = rewriter.rewrite(plainSelect, tableContext(), ProjectionMode.NORMAL);

        assertTrue(changed);
        assertEquals(1, countOccurrences(plainSelect.toString(), "phone_cipher"));
        assertTrue(plainSelect.toString().contains("phone_cipher AS phone, user_account.*")
                || plainSelect.toString().contains("phone_cipher phone, user_account.*"));
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
        Statement statement = JSqlParserSupport.parseStatement(sql);
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

    private SqlTableContext sameColumnStorageTableContext() {
        EncryptTableRule tableRule = new EncryptTableRule("user_account");
        tableRule.addColumnRule(phoneRule("phone"));
        SqlTableContext tableContext = new SqlTableContext();
        tableContext.register("user_account", null, tableRule);
        return tableContext;
    }

    private EncryptColumnRule phoneRule() {
        return phoneRule("phone_cipher");
    }

    private EncryptColumnRule phoneRule(String storageColumn) {
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
                storageColumn,
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

    private int countOccurrences(String value, String segment) {
        int count = 0;
        int fromIndex = 0;
        while (true) {
            int index = value.indexOf(segment, fromIndex);
            if (index < 0) {
                return count;
            }
            count++;
            fromIndex = index + segment.length();
        }
    }
}
