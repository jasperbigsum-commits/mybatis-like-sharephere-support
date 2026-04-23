package io.github.jasper.mybatis.encrypt.core.rewrite;

import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import io.github.jasper.mybatis.encrypt.exception.EncryptionErrorCode;
import io.github.jasper.mybatis.encrypt.exception.UnsupportedEncryptedOperationException;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.CaseExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.NotExpression;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.WhenClause;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.GreaterThan;
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.expression.operators.relational.LikeExpression;
import net.sf.jsqlparser.expression.operators.relational.MinorThan;
import net.sf.jsqlparser.expression.operators.relational.MinorThanEquals;
import net.sf.jsqlparser.expression.operators.relational.NotEqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;

import java.util.List;
import java.util.function.BiFunction;

/**
 * 查询条件改写器。
 */
final class SqlConditionRewriter {

    private final EncryptionValueTransformer valueTransformer;
    private final SelectRewriteDispatcher selectRewriteDispatcher;
    private final SqlConditionOperandSupport operandSupport;
    private final SqlSeparateTableExistsConditionBuilder separateTableExistsConditionBuilder;
    private final SqlEqualityConditionRewriter sqlEqualityConditionRewriter;
    private final SqlLikeConditionRewriter sqlLikeConditionRewriter;
    private final BiFunction<Column, String, Column> columnBuilder;
    private final BiFunction<EncryptColumnRule, String, String> assistedQueryColumnProvider;

    SqlConditionRewriter(EncryptionValueTransformer valueTransformer,
                         BiFunction<Column, String, Column> columnBuilder,
                         BiFunction<EncryptColumnRule, String, String> assistedQueryColumnProvider,
                         BiFunction<EncryptColumnRule, String, String> likeQueryColumnProvider,
                         java.util.function.Function<String, String> identifierQuoter,
                         SelectRewriteDispatcher selectRewriteDispatcher) {
        this.valueTransformer = valueTransformer;
        this.columnBuilder = columnBuilder;
        this.assistedQueryColumnProvider = assistedQueryColumnProvider;
        this.selectRewriteDispatcher = selectRewriteDispatcher;
        this.operandSupport = new SqlConditionOperandSupport();
        this.separateTableExistsConditionBuilder = new SqlSeparateTableExistsConditionBuilder(columnBuilder, identifierQuoter);
        this.sqlEqualityConditionRewriter = new SqlEqualityConditionRewriter(
                valueTransformer,
                columnBuilder,
                assistedQueryColumnProvider,
                operandSupport,
                separateTableExistsConditionBuilder
        );
        this.sqlLikeConditionRewriter = new SqlLikeConditionRewriter(
                valueTransformer,
                columnBuilder,
                assistedQueryColumnProvider,
                likeQueryColumnProvider,
                operandSupport,
                separateTableExistsConditionBuilder
        );
    }

    void consumeSelectItemParameters(List<SelectItem<?>> selectItems, SqlRewriteContext context) {
        if (selectItems == null) {
            return;
        }
        for (SelectItem<?> item : selectItems) {
            consumeExpression(item.getExpression(), context);
        }
    }

    void consume(Expression expression, SqlRewriteContext context) {
        consumeExpression(expression, context);
    }

    Expression rewrite(Expression expression, SqlTableContext tableContext, SqlRewriteContext context) {
        if (expression == null) {
            return null;
        }
        if (expression instanceof Parenthesis) {
            Parenthesis parenthesis = (Parenthesis) expression;
            parenthesis.setExpression(rewrite(parenthesis.getExpression(), tableContext, context));
            return parenthesis;
        }
        if (expression instanceof ParenthesedExpressionList) {
            @SuppressWarnings("rawtypes")
            ParenthesedExpressionList parenthesis = (ParenthesedExpressionList) expression;
            parenthesis.replaceAll(exp -> rewrite((Expression) exp, tableContext, context));
            return parenthesis;
        }
        if (expression instanceof ExistsExpression) {
            ExistsExpression existsExpression = (ExistsExpression) expression;
            if (existsExpression.getRightExpression() instanceof Select) {
                selectRewriteDispatcher.rewrite((Select) existsExpression.getRightExpression(), context, ProjectionMode.NORMAL);
            }
            return existsExpression;
        }
        if (expression instanceof NotExpression) {
            NotExpression notExpression = (NotExpression) expression;
            notExpression.setExpression(rewrite(notExpression.getExpression(), tableContext, context));
            return notExpression;
        }
        if (expression instanceof CaseExpression) {
            CaseExpression caseExpression = (CaseExpression) expression;
            if (caseExpression.getSwitchExpression() != null) {
                caseExpression.setSwitchExpression(rewrite(caseExpression.getSwitchExpression(), tableContext, context));
            }
            if (caseExpression.getWhenClauses() != null) {
                for (WhenClause whenClause : caseExpression.getWhenClauses()) {
                    whenClause.setWhenExpression(rewrite(whenClause.getWhenExpression(), tableContext, context));
                    whenClause.setThenExpression(rewrite(whenClause.getThenExpression(), tableContext, context));
                }
            }
            if (caseExpression.getElseExpression() != null) {
                caseExpression.setElseExpression(rewrite(caseExpression.getElseExpression(), tableContext, context));
            }
            return caseExpression;
        }
        if (expression instanceof Select) {
            selectRewriteDispatcher.rewrite((Select) expression, context, ProjectionMode.NORMAL);
            return expression;
        }
        if (expression instanceof AndExpression) {
            AndExpression andExpression = (AndExpression) expression;
            andExpression.setLeftExpression(rewrite(andExpression.getLeftExpression(), tableContext, context));
            andExpression.setRightExpression(rewrite(andExpression.getRightExpression(), tableContext, context));
            return andExpression;
        }
        if (expression instanceof OrExpression) {
            OrExpression orExpression = (OrExpression) expression;
            orExpression.setLeftExpression(rewrite(orExpression.getLeftExpression(), tableContext, context));
            orExpression.setRightExpression(rewrite(orExpression.getRightExpression(), tableContext, context));
            return orExpression;
        }
        if (expression instanceof EqualsTo) {
            return rewriteEquality((EqualsTo) expression, tableContext, context);
        }
        if (expression instanceof NotEqualsTo) {
            return rewriteEquality((NotEqualsTo) expression, tableContext, context);
        }
        if (expression instanceof LikeExpression) {
            return rewriteLikeCondition((LikeExpression) expression, tableContext, context);
        }
        if (expression instanceof InExpression) {
            return rewriteInCondition((InExpression) expression, tableContext, context);
        }
        if (expression instanceof IsNullExpression) {
            return rewriteIsNullCondition((IsNullExpression) expression, tableContext, context);
        }
        if (expression instanceof Between) {
            Between between = (Between) expression;
            validateNonRangeEncryptedColumn(between.getLeftExpression(), tableContext,
                    EncryptionErrorCode.UNSUPPORTED_ENCRYPTED_RANGE,
                    "BETWEEN is not supported on encrypted fields.");
            consumeExpression(between.getBetweenExpressionStart(), context);
            consumeExpression(between.getBetweenExpressionEnd(), context);
            return between;
        }
        if (expression instanceof GreaterThan || expression instanceof GreaterThanEquals
                || expression instanceof MinorThan || expression instanceof MinorThanEquals) {
            BinaryExpression binaryExpression = (BinaryExpression) expression;
            validateNonRangeEncryptedColumn(binaryExpression.getLeftExpression(), tableContext,
                    EncryptionErrorCode.UNSUPPORTED_ENCRYPTED_RANGE,
                    "Range comparison is not supported on encrypted fields.");
            consumeExpression(binaryExpression.getRightExpression(), context);
            return expression;
        }
        if (expression instanceof BinaryExpression) {
            BinaryExpression binaryExpression = (BinaryExpression) expression;
            binaryExpression.setLeftExpression(rewrite(binaryExpression.getLeftExpression(), tableContext, context));
            binaryExpression.setRightExpression(rewrite(binaryExpression.getRightExpression(), tableContext, context));
            return binaryExpression;
        }
        if (expression instanceof Function && ((Function) expression).getParameters() != null) {
            Function function = (Function) expression;
            for (Expression item : function.getParameters()) {
                rewrite(item, tableContext, context);
            }
            return expression;
        }
        if (expression instanceof JdbcParameter) {
            context.consumeOriginal();
        }
        return expression;
    }

    private Expression rewriteEquality(BinaryExpression expression, SqlTableContext tableContext, SqlRewriteContext context) {
        ColumnResolution resolution = resolveComparison(expression, tableContext);
        if (resolution == null) {
            expression.setLeftExpression(rewrite(expression.getLeftExpression(), tableContext, context));
            expression.setRightExpression(rewrite(expression.getRightExpression(), tableContext, context));
            return expression;
        }
        return sqlEqualityConditionRewriter.rewrite(expression, resolution, context);
    }

    private Expression rewriteLikeCondition(LikeExpression expression, SqlTableContext tableContext, SqlRewriteContext context) {
        ColumnResolution resolution = resolveEncryptedColumn(expression.getLeftExpression(), tableContext);
        if (resolution == null) {
            expression.setLeftExpression(rewrite(expression.getLeftExpression(), tableContext, context));
            expression.setRightExpression(rewrite(expression.getRightExpression(), tableContext, context));
            return expression;
        }
        return sqlLikeConditionRewriter.rewrite(expression, resolution, context);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Expression rewriteInCondition(InExpression expression, SqlTableContext tableContext, SqlRewriteContext context) {
        ColumnResolution resolution = resolveEncryptedColumn(expression.getLeftExpression(), tableContext);
        if (resolution == null) {
            if (expression.getRightExpression() instanceof Select) {
                selectRewriteDispatcher.rewrite((Select) expression.getRightExpression(), context, ProjectionMode.NORMAL);
            }
            consumeItemsList(expression.getRightExpression(), context);
            return expression;
        }
        EncryptColumnRule rule = resolution.rule();
        if (expression.getRightExpression() instanceof Select) {
            if (rule.isStoredInSeparateTable()) {
                throw new UnsupportedEncryptedOperationException(EncryptionErrorCode.UNSUPPORTED_ENCRYPTED_IN_QUERY,
                        "IN subquery is not supported for separate-table encrypted field: " + rule.property());
            }
            expression.setLeftExpression(columnBuilder.apply(resolution.column(),
                    assistedQueryColumnProvider.apply(rule, "IN query")));
            selectRewriteDispatcher.rewrite((Select) expression.getRightExpression(), context, ProjectionMode.COMPARISON);
            context.markChanged();
            return expression;
        }
        if (!(expression.getRightExpression() instanceof ExpressionList<?>)) {
            throw new UnsupportedEncryptedOperationException(EncryptionErrorCode.INVALID_ENCRYPTED_QUERY_OPERAND,
                    "Unsupported IN operand for encrypted fields.");
        }
        // 独立表主表字段保存的就是外表关联 hash/ref，列表型 IN 可以直接比较主表字段，避免逐值 EXISTS。
        String targetColumn = rule.isStoredInSeparateTable()
                ? rule.column()
                : assistedQueryColumnProvider.apply(rule, "IN query");
        expression.setLeftExpression(columnBuilder.apply(resolution.column(), targetColumn));
        ExpressionList expressionList = (ExpressionList) expression.getRightExpression();
        for (int index = 0; index < expressionList.size(); index++) {
            Expression item = (Expression) expressionList.get(index);
            expressionList.set(index, rewriteAssistedOperand(rule, item, context,
                    "Encrypted IN condition must use prepared parameter, string literal, or CONCAT of them."));
        }
        context.markChanged();
        return expression;
    }

    private Expression rewriteIsNullCondition(IsNullExpression expression, SqlTableContext tableContext, SqlRewriteContext context) {
        ColumnResolution resolution = resolveEncryptedColumn(expression.getLeftExpression(), tableContext);
        if (resolution == null) {
            expression.setLeftExpression(rewrite(expression.getLeftExpression(), tableContext, context));
            return expression;
        }
        EncryptColumnRule rule = resolution.rule();
        if (rule.isStoredInSeparateTable()) {
            context.markChanged();
            return separateTableExistsConditionBuilder.buildPresenceCondition(resolution.column(), rule, expression.isNot());
        }
        expression.setLeftExpression(columnBuilder.apply(resolution.column(), rule.storageColumn()));
        context.markChanged();
        return expression;
    }

    private void consumeExpression(Expression expression, SqlRewriteContext context) {
        if (expression == null) {
            return;
        }
        if (expression instanceof JdbcParameter) {
            context.consumeOriginal();
            return;
        }
        if (expression instanceof Parenthesis) {
            consumeExpression(((Parenthesis) expression).getExpression(), context);
            return;
        }
        if (expression instanceof ParenthesedExpressionList) {
            ParenthesedExpressionList<?> parenthesis = (ParenthesedExpressionList<?>) expression;
            for (Expression exp : parenthesis) {
                consumeExpression(exp, context);
            }
            return;
        }
        if (expression instanceof BinaryExpression) {
            BinaryExpression binaryExpression = (BinaryExpression) expression;
            consumeExpression(binaryExpression.getLeftExpression(), context);
            consumeExpression(binaryExpression.getRightExpression(), context);
            return;
        }
        if (expression instanceof CaseExpression) {
            CaseExpression caseExpression = (CaseExpression) expression;
            consumeExpression(caseExpression.getSwitchExpression(), context);
            if (caseExpression.getWhenClauses() != null) {
                for (WhenClause whenClause : caseExpression.getWhenClauses()) {
                    consumeExpression(whenClause.getWhenExpression(), context);
                    consumeExpression(whenClause.getThenExpression(), context);
                }
            }
            consumeExpression(caseExpression.getElseExpression(), context);
            return;
        }
        if (expression instanceof Function && ((Function) expression).getParameters() != null) {
            Function function = (Function) expression;
            for (Expression item : function.getParameters()) {
                consumeExpression(item, context);
            }
        }
    }

    private void consumeItemsList(Object itemsList, SqlRewriteContext context) {
        if (itemsList instanceof ExpressionList<?>) {
            ExpressionList<?> expressionList = (ExpressionList<?>) itemsList;
            for (Expression item : expressionList) {
                consumeExpression(item, context);
            }
        }
    }

    private void validateNonRangeEncryptedColumn(Expression expression,
                                                 SqlTableContext tableContext,
                                                 EncryptionErrorCode errorCode,
                                                 String message) {
        if (resolveEncryptedColumn(expression, tableContext) != null) {
            throw new UnsupportedEncryptedOperationException(errorCode, message);
        }
    }

    private ColumnResolution resolveComparison(BinaryExpression expression, SqlTableContext tableContext) {
        ColumnResolution left = resolveEncryptedColumn(expression.getLeftExpression(), tableContext);
        if (left != null) {
            return new ColumnResolution(left.column(), left.rule(), true);
        }
        ColumnResolution right = resolveEncryptedColumn(expression.getRightExpression(), tableContext);
        return right == null ? null : new ColumnResolution(right.column(), right.rule(), false);
    }

    private ColumnResolution resolveEncryptedColumn(Expression expression, SqlTableContext tableContext) {
        if (!(expression instanceof Column)) {
            return null;
        }
        Column column = (Column) expression;
        EncryptColumnRule rule = tableContext.resolve(column).orElse(null);
        return rule == null ? null : new ColumnResolution(column, rule, true);
    }

    private Expression rewriteAssistedOperand(EncryptColumnRule rule,
                                              Expression operand,
                                              SqlRewriteContext context,
                                              String unsupportedMessage) {
        QueryOperand queryOperand = operandSupport.readComposableQueryOperand(operand, context,
                EncryptionErrorCode.INVALID_ENCRYPTED_QUERY_OPERAND, unsupportedMessage);
        return operandSupport.buildComposableQueryExpression(queryOperand, context,
                valueTransformer.transformAssisted(rule, queryOperand.value()),
                MaskingMode.HASH);
    }

    @FunctionalInterface
    interface SelectRewriteDispatcher {
        void rewrite(Select select, SqlRewriteContext context, ProjectionMode projectionMode);
    }
}
