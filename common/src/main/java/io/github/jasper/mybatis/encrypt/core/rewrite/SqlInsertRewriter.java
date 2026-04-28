package io.github.jasper.mybatis.encrypt.core.rewrite;

import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptTableRule;
import io.github.jasper.mybatis.encrypt.exception.EncryptionErrorCode;
import io.github.jasper.mybatis.encrypt.exception.UnsupportedEncryptedOperationException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Parenthesis;
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
 *
 * <p>Handles both single-row and multi-row (batch) INSERT. Multi-row inserts
 * build a column-rewrite plan once, then apply per-row expression rewrites
 * to maintain correct correspondence between columns and row values.</p>
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
        if (isMultiRow(originalExpressions)) {
            return rewriteMultiRow(originalExpressions, originalColumns, insert, values, tableRule, context);
        }
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

    private boolean isMultiRow(ExpressionList<?> expressions) {
        if (expressions.isEmpty()) {
            return false;
        }
        if (expressions instanceof ParenthesedExpressionList) {
            return false;
        }
        return expressions.get(0) instanceof ExpressionList
                || expressions.get(0) instanceof Parenthesis;
    }

    /**
     * Rewrites a multi-row (batch) INSERT.
     *
     * <p>Multi-row VALUES like {@code VALUES (?, ?), (?, ?)} are represented as an
     * {@code ExpressionList} of {@code ParenthesedExpressionList} rows. Single-column
     * multi-row VALUES like {@code VALUES (?), (?)} use {@code Parenthesis} per row
     * instead. This method builds a column-rewrite plan from the column list once,
     * then applies per-row expression rewrites.</p>
     */
    private boolean rewriteMultiRow(ExpressionList<?> rows,
                                    List<Column> originalColumns,
                                    Insert insert,
                                    Values values,
                                    EncryptTableRule tableRule,
                                    SqlRewriteContext context) {
        boolean hasEncrypted = false;
        for (Column column : originalColumns) {
            if (tableRule.findByColumn(column.getColumnName()).isPresent()) {
                hasEncrypted = true;
                break;
            }
        }
        if (!hasEncrypted) {
            return false;
        }
        List<Column> rewrittenColumns = new ArrayList<>();
        List<EncryptColumnRule> columnRules = new ArrayList<>();
        for (Column column : originalColumns) {
            EncryptColumnRule rule = tableRule.findByColumn(column.getColumnName()).orElse(null);
            columnRules.add(rule);
            if (rule == null) {
                rewrittenColumns.add(column);
            } else if (rule.isStoredInSeparateTable()) {
                rewrittenColumns.add(column);
            } else {
                rewrittenColumns.add(buildColumn(rule.storageColumn()));
                if (rule.hasAssistedQueryColumn()) {
                    rewrittenColumns.add(buildColumn(rule.assistedQueryColumn()));
                }
                if (rule.hasLikeQueryColumn()) {
                    rewrittenColumns.add(buildColumn(rule.likeQueryColumn()));
                }
                if (rule.hasDistinctMaskedColumn()) {
                    rewrittenColumns.add(buildColumn(rule.maskedColumn()));
                }
            }
        }
        List<Expression> rewrittenRows = new ArrayList<>();
        for (Expression row : rows) {
            List<Expression> newRowExpressions = new ArrayList<>();
            for (int colIndex = 0; colIndex < originalColumns.size(); colIndex++) {
                Expression expression;
                if (row instanceof ParenthesedExpressionList) {
                    expression = ((ParenthesedExpressionList<?>) row).get(colIndex);
                } else if (row instanceof Parenthesis) {
                    expression = ((Parenthesis) row).getExpression();
                } else {
                    expression = row;
                }
                EncryptColumnRule rule = columnRules.get(colIndex);
                if (rule == null) {
                    newRowExpressions.add(writeExpressionRewriter.passthrough(expression, context));
                } else if (rule.isStoredInSeparateTable()) {
                    newRowExpressions.add(writeExpressionRewriter.rewriteSeparateTableReference(expression, context));
                } else {
                    WriteValue writeValue = writeExpressionRewriter.rewriteEncrypted(expression, rule, context);
                    newRowExpressions.add(writeValue.expression());
                    if (rule.hasAssistedQueryColumn()) {
                        newRowExpressions.add(writeExpressionRewriter.buildShadow(
                                writeValue,
                                valueTransformer.transformAssisted(rule, writeValue.plainValue()),
                                MaskingMode.HASH,
                                context
                        ));
                    }
                    if (rule.hasLikeQueryColumn()) {
                        newRowExpressions.add(writeExpressionRewriter.buildShadow(
                                writeValue,
                                valueTransformer.transformLike(rule, writeValue.plainValue()),
                                MaskingMode.MASKED,
                                context
                        ));
                    }
                    if (rule.hasDistinctMaskedColumn()) {
                        newRowExpressions.add(writeExpressionRewriter.buildShadow(
                                writeValue,
                                valueTransformer.transformMasked(rule, writeValue.plainValue()),
                                MaskingMode.MASKED,
                                context
                        ));
                    }
                }
            }
            rewrittenRows.add(new ParenthesedExpressionList<>(newRowExpressions));
        }
        insert.setColumns(new ExpressionList<>(rewrittenColumns));
        values.setExpressions(new ExpressionList<>(rewrittenRows));
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
        if (rule.hasDistinctMaskedColumn()) {
            rewrittenColumns.add(buildColumn(rule.maskedColumn()));
            rewrittenExpressions.add(writeExpressionRewriter.buildShadow(
                    writeValue,
                    valueTransformer.transformMasked(rule, writeValue.plainValue()),
                    MaskingMode.MASKED,
                    context
            ));
        }
    }

    private Column buildColumn(String columnName) {
        return new Column(identifierQuoter.apply(columnName));
    }
}
