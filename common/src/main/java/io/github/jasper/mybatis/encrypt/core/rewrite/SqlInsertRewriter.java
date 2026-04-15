package io.github.jasper.mybatis.encrypt.core.rewrite;

import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptTableRule;
import io.github.jasper.mybatis.encrypt.exception.EncryptionErrorCode;
import io.github.jasper.mybatis.encrypt.exception.UnsupportedEncryptedOperationException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Values;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Rewrites encrypted INSERT VALUES statements.
 */
final class SqlInsertRewriter {

    private final SqlWriteExpressionRewriter writeExpressionRewriter;
    private final EncryptionValueTransformer valueTransformer;
    private final Function<String, String> identifierQuoter;

    SqlInsertRewriter(SqlWriteExpressionRewriter writeExpressionRewriter,
                      EncryptionValueTransformer valueTransformer,
                      Function<String, String> identifierQuoter) {
        this.writeExpressionRewriter = writeExpressionRewriter;
        this.valueTransformer = valueTransformer;
        this.identifierQuoter = identifierQuoter;
    }

    boolean rewrite(Insert insert, EncryptTableRule tableRule, SqlRewriteContext context) {
        Values values;
        try {
            values = insert.getValues();
        } catch (ClassCastException ex) {
            values = null;
        }
        if (values == null || values.getExpressions() == null) {
            throw new UnsupportedEncryptedOperationException(EncryptionErrorCode.UNSUPPORTED_ENCRYPTED_INSERT,
                    "Only VALUES inserts are supported for encrypted tables.");
        }
        ExpressionList<?> originalExpressions = values.getExpressions();
        List<Column> originalColumns = new ArrayList<>(insert.getColumns());
        List<Column> rewrittenColumns = new ArrayList<>();
        List<Expression> rewrittenExpressions = new ArrayList<>();
        boolean changed = false;
        for (int index = 0; index < originalColumns.size(); index++) {
            Column column = originalColumns.get(index);
            Expression expression = originalExpressions.get(index);
            EncryptColumnRule rule = tableRule.findByColumn(column.getColumnName()).orElse(null);
            if (rule == null) {
                rewrittenColumns.add(column);
                rewrittenExpressions.add(writeExpressionRewriter.passthrough(expression, context));
                continue;
            }
            changed = true;
            if (rule.isStoredInSeparateTable()) {
                rewrittenColumns.add(column);
                rewrittenExpressions.add(writeExpressionRewriter.rewriteSeparateTableReference(expression, context));
                continue;
            }
            rewrittenColumns.add(buildColumn(rule.storageColumn()));
            WriteValue writeValue = writeExpressionRewriter.rewriteEncrypted(expression, rule, context);
            rewrittenExpressions.add(writeValue.expression());
            appendShadowColumns(rule, writeValue, rewrittenColumns, rewrittenExpressions, context);
        }
        if (!changed) {
            return false;
        }
        insert.setColumns(new ExpressionList<>(rewrittenColumns));
        values.setExpressions(new ParenthesedExpressionList<>(rewrittenExpressions));
        return true;
    }

    private void appendShadowColumns(EncryptColumnRule rule,
                                     WriteValue writeValue,
                                     List<Column> rewrittenColumns,
                                     List<Expression> rewrittenExpressions,
                                     SqlRewriteContext context) {
        if (rule.hasAssistedQueryColumn()) {
            rewrittenColumns.add(buildColumn(rule.assistedQueryColumn()));
            rewrittenExpressions.add(writeExpressionRewriter.buildShadow(
                    writeValue,
                    valueTransformer.transformAssisted(rule, writeValue.plainValue()),
                    MaskingMode.HASH,
                    context
            ));
        }
        if (rule.hasLikeQueryColumn()) {
            rewrittenColumns.add(buildColumn(rule.likeQueryColumn()));
            rewrittenExpressions.add(writeExpressionRewriter.buildShadow(
                    writeValue,
                    valueTransformer.transformLike(rule, writeValue.plainValue()),
                    MaskingMode.MASKED,
                    context
            ));
        }
    }

    private Column buildColumn(String columnName) {
        return new Column(identifierQuoter.apply(columnName));
    }
}
