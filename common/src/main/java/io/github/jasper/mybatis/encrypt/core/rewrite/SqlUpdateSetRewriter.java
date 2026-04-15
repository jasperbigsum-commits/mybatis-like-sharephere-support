package io.github.jasper.mybatis.encrypt.core.rewrite;

import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import io.github.jasper.mybatis.encrypt.exception.UnsupportedEncryptedOperationException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.update.UpdateSet;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Rewrites encrypted UPDATE SET clauses.
 */
final class SqlUpdateSetRewriter {

    private final SqlWriteExpressionRewriter writeExpressionRewriter;
    private final EncryptionValueTransformer valueTransformer;
    private final BiFunction<Column, String, Column> columnBuilder;

    SqlUpdateSetRewriter(SqlWriteExpressionRewriter writeExpressionRewriter,
                         EncryptionValueTransformer valueTransformer,
                         BiFunction<Column, String, Column> columnBuilder) {
        this.writeExpressionRewriter = writeExpressionRewriter;
        this.valueTransformer = valueTransformer;
        this.columnBuilder = columnBuilder;
    }

    boolean rewrite(Update update, SqlTableContext tableContext, SqlRewriteContext context) {
        List<UpdateSet> rewrittenUpdateSets = new ArrayList<>();
        boolean changed = false;
        for (UpdateSet updateSet : update.getUpdateSets()) {
            List<Column> originalColumns = new ArrayList<>(updateSet.getColumns());
            ExpressionList<Expression> updateValues = castValues(updateSet.getValues());
            for (int index = 0; index < originalColumns.size(); index++) {
                Column column = originalColumns.get(index);
                Expression expression = updateValues.get(index);
                EncryptColumnRule rule = tableContext.resolve(column).orElse(null);
                if (rule == null) {
                    rewrittenUpdateSets.add(new UpdateSet(column, writeExpressionRewriter.passthrough(expression, context)));
                    continue;
                }
                changed = true;
                if (rule.isStoredInSeparateTable()) {
                    rewrittenUpdateSets.add(new UpdateSet(
                            column,
                            writeExpressionRewriter.rewriteSeparateTableReference(expression, context)));
                    continue;
                }
                WriteValue writeValue = writeExpressionRewriter.rewriteEncrypted(expression, rule, context);
                rewrittenUpdateSets.add(new UpdateSet(columnBuilder.apply(column, rule.storageColumn()), writeValue.expression()));
                appendShadowUpdateSets(rule, column, writeValue, rewrittenUpdateSets, context);
            }
        }
        if (!changed) {
            return false;
        }
        update.setUpdateSets(rewrittenUpdateSets);
        return true;
    }

    @SuppressWarnings("unchecked")
    private ExpressionList<Expression> castValues(Object values) {
        if (values instanceof ExpressionList) {
            return (ExpressionList<Expression>) values;
        }
        throw new UnsupportedEncryptedOperationException(
                io.github.jasper.mybatis.encrypt.exception.EncryptionErrorCode.INVALID_ENCRYPTED_WRITE_OPERAND,
                "Encrypted update only supports expression list assignments."
        );
    }

    private void appendShadowUpdateSets(EncryptColumnRule rule,
                                        Column sourceColumn,
                                        WriteValue writeValue,
                                        List<UpdateSet> rewrittenUpdateSets,
                                        SqlRewriteContext context) {
        if (rule.hasAssistedQueryColumn()) {
            rewrittenUpdateSets.add(new UpdateSet(
                    columnBuilder.apply(sourceColumn, rule.assistedQueryColumn()),
                    writeExpressionRewriter.buildShadow(
                            writeValue,
                            valueTransformer.transformAssisted(rule, writeValue.plainValue()),
                            MaskingMode.HASH,
                            context
                    )));
        }
        if (rule.hasLikeQueryColumn()) {
            rewrittenUpdateSets.add(new UpdateSet(
                    columnBuilder.apply(sourceColumn, rule.likeQueryColumn()),
                    writeExpressionRewriter.buildShadow(
                            writeValue,
                            valueTransformer.transformLike(rule, writeValue.plainValue()),
                            MaskingMode.MASKED,
                            context
                    )));
        }
    }
}
