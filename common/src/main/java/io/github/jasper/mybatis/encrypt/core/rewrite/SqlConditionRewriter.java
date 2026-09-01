package io.github.jasper.mybatis.encrypt.core.rewrite;

import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptJsonPathRule;
import io.github.jasper.mybatis.encrypt.exception.EncryptionErrorCode;
import io.github.jasper.mybatis.encrypt.exception.UnsupportedEncryptedOperationException;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.CaseExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.NotExpression;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.StringValue;
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
import java.util.function.Consumer;

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
    private final Consumer<EncryptColumnRule> rangeWarningConsumer;

    SqlConditionRewriter(EncryptionValueTransformer valueTransformer,
                         BiFunction<Column, String, Column> columnBuilder,
                         BiFunction<EncryptColumnRule, String, String> assistedQueryColumnProvider,
                         BiFunction<EncryptColumnRule, String, String> likeQueryColumnProvider,
                         java.util.function.Function<String, String> identifierQuoter,
                         SelectRewriteDispatcher selectRewriteDispatcher) {
        this(valueTransformer, columnBuilder, assistedQueryColumnProvider, likeQueryColumnProvider,
                identifierQuoter, selectRewriteDispatcher, rule -> {
                });
    }

    SqlConditionRewriter(EncryptionValueTransformer valueTransformer,
                         BiFunction<Column, String, Column> columnBuilder,
                         BiFunction<EncryptColumnRule, String, String> assistedQueryColumnProvider,
                         BiFunction<EncryptColumnRule, String, String> likeQueryColumnProvider,
                         java.util.function.Function<String, String> identifierQuoter,
                         SelectRewriteDispatcher selectRewriteDispatcher,
                         Consumer<EncryptColumnRule> rangeWarningConsumer) {
        this.valueTransformer = valueTransformer;
        this.columnBuilder = columnBuilder;
        this.assistedQueryColumnProvider = assistedQueryColumnProvider;
        this.selectRewriteDispatcher = selectRewriteDispatcher;
        this.rangeWarningConsumer = rangeWarningConsumer;
        this.operandSupport = new SqlConditionOperandSupport();
        this.separateTableExistsConditionBuilder = new SqlSeparateTableExistsConditionBuilder(columnBuilder, identifierQuoter);
        this.sqlEqualityConditionRewriter = new SqlEqualityConditionRewriter(
                valueTransformer,
                columnBuilder,
                assistedQueryColumnProvider,
                operandSupport
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
                selectRewriteDispatcher.rewrite((Select) existsExpression.getRightExpression(),
                        context, ProjectionMode.NORMAL, tableContext);
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
            selectRewriteDispatcher.rewrite((Select) expression, context, ProjectionMode.NORMAL, tableContext);
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
            return rewriteRangeComparison((BinaryExpression) expression, tableContext, context);
        }
        if (expression instanceof BinaryExpression) {
            BinaryExpression binaryExpression = (BinaryExpression) expression;
            binaryExpression.setLeftExpression(rewrite(binaryExpression.getLeftExpression(), tableContext, context));
            binaryExpression.setRightExpression(rewrite(binaryExpression.getRightExpression(), tableContext, context));
            return binaryExpression;
        }
        if (expression instanceof Function && ((Function) expression).getParameters() != null) {
            rewriteFunctionCondition((Function) expression, tableContext, context);
            return expression;
        }
        if (expression instanceof JdbcParameter) {
            context.consumeOriginal();
        }
        return expression;
    }

    private void rewriteFunctionCondition(Function function,
                                          SqlTableContext tableContext,
                                          SqlRewriteContext context) {
        if (function.getName() != null && "find_in_set".equalsIgnoreCase(function.getName())) {
            rewriteFindInSetCondition(function, tableContext, context);
            return;
        }
        rewriteFunctionParameters(function, tableContext, context);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void rewriteFindInSetCondition(Function function,
                                           SqlTableContext tableContext,
                                           SqlRewriteContext context) {
        ExpressionList parameters = function.getParameters();
        if (parameters.size() != 2) {
            rewriteFunctionParameters(function, tableContext, context);
            return;
        }
        Object first = parameters.get(0);
        Object second = parameters.get(1);
        if (!(first instanceof Expression) || !(second instanceof Expression)) {
            return;
        }
        ColumnResolution resolution = resolveEncryptedColumn((Expression) first, tableContext);
        if (resolution == null) {
            rewriteFunctionParameters(function, tableContext, context);
            return;
        }
        EncryptColumnRule rule = resolution.rule();
        String targetColumn = rule.isStoredInSeparateTable()
                ? rule.column()
                : assistedQueryColumnProvider.apply(rule, "FIND_IN_SET query");
        parameters.set(0, columnBuilder.apply(resolution.column(), targetColumn));
        parameters.set(1, rewriteFindInSetCandidates(rule, (Expression) second, context));
        context.markChanged();
    }

    private Expression rewriteFindInSetCandidates(EncryptColumnRule rule,
                                                  Expression operand,
                                                  SqlRewriteContext context) {
        QueryOperand queryOperand = operandSupport.readComposableQueryOperand(operand, context,
                EncryptionErrorCode.INVALID_ENCRYPTED_QUERY_OPERAND,
                "Encrypted FIND_IN_SET condition must use prepared parameter, string literal, or CONCAT of them.");
        Object value = queryOperand.value();
        if (value == null) {
            return operandSupport.buildComposableQueryExpression(queryOperand, context, null, MaskingMode.HASH);
        }
        String[] items = String.valueOf(value).split(",", -1);
        StringBuilder transformed = new StringBuilder();
        for (int index = 0; index < items.length; index++) {
            if (index > 0) {
                transformed.append(',');
            }
            transformed.append(valueTransformer.transformAssisted(rule, items[index].trim()));
        }
        return operandSupport.buildComposableQueryExpression(queryOperand, context, transformed.toString(), MaskingMode.HASH);
    }

    @SuppressWarnings("unchecked")
    private void rewriteFunctionParameters(Function function,
                                           SqlTableContext tableContext,
                                           SqlRewriteContext context) {
        if (function.getParameters() == null) {
            return;
        }
        for (Expression item : function.getParameters()) {
            rewrite(item, tableContext, context);
        }
    }

    private Expression rewriteEquality(BinaryExpression expression, SqlTableContext tableContext, SqlRewriteContext context) {
        JsonExtractResolution jsonResolution = resolveEncryptedJsonExtract(expression.getLeftExpression(), tableContext);
        if (jsonResolution != null) {
            expression.setRightExpression(rewriteJsonAssistedOperand(
                    jsonResolution.pathRule(),
                    expression.getRightExpression(),
                    context,
                    "Encrypted JSON equality condition must use prepared parameter, string literal, or CONCAT of them."
            ));
            context.markChanged();
            return expression;
        }
        jsonResolution = resolveEncryptedJsonExtract(expression.getRightExpression(), tableContext);
        if (jsonResolution != null) {
            expression.setLeftExpression(rewriteJsonAssistedOperand(
                    jsonResolution.pathRule(),
                    expression.getLeftExpression(),
                    context,
                    "Encrypted JSON equality condition must use prepared parameter, string literal, or CONCAT of them."
            ));
            context.markChanged();
            return expression;
        }
        ColumnResolution left = resolveEncryptedColumn(expression.getLeftExpression(), tableContext);
        ColumnResolution right = resolveEncryptedColumn(expression.getRightExpression(), tableContext);
        if (left != null && right != null) {
            return sqlEqualityConditionRewriter.rewriteColumnComparison(expression, left, right, context);
        }
        ColumnResolution resolution = left != null ? new ColumnResolution(left.column(), left.rule(), true)
                : right == null ? null : new ColumnResolution(right.column(), right.rule(), false);
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

    private Expression rewriteRangeComparison(BinaryExpression expression,
                                              SqlTableContext tableContext,
                                              SqlRewriteContext context) {
        ColumnResolution left = resolveEncryptedColumn(expression.getLeftExpression(), tableContext);
        ColumnResolution right = resolveEncryptedColumn(expression.getRightExpression(), tableContext);
        if (left != null && right != null) {
            throw new UnsupportedEncryptedOperationException(EncryptionErrorCode.UNSUPPORTED_ENCRYPTED_OPERATION,
                    "Range comparison between two encrypted columns is not supported.");
        }
        ColumnResolution resolution = left != null ? new ColumnResolution(left.column(), left.rule(), true)
                : right == null ? null : new ColumnResolution(right.column(), right.rule(), false);
        if (resolution == null) {
            expression.setLeftExpression(rewrite(expression.getLeftExpression(), tableContext, context));
            expression.setRightExpression(rewrite(expression.getRightExpression(), tableContext, context));
            return expression;
        }
        rangeWarningConsumer.accept(resolution.rule());
        EncryptColumnRule rule = resolution.rule();
        String targetColumn = rule.isStoredInSeparateTable()
                ? rule.column()
                : assistedQueryColumnProvider.apply(rule, "range query");
        Expression operand = resolution.leftColumn() ? expression.getRightExpression() : expression.getLeftExpression();
        Expression rewrittenOperand = rewriteAssistedOperand(rule, operand, context,
                "Encrypted range condition must use prepared parameter, string literal, or CONCAT of them.");
        if (resolution.leftColumn()) {
            expression.setLeftExpression(columnBuilder.apply(resolution.column(), targetColumn));
            expression.setRightExpression(rewrittenOperand);
        } else {
            expression.setLeftExpression(rewrittenOperand);
            expression.setRightExpression(columnBuilder.apply(resolution.column(), targetColumn));
        }
        context.markChanged();
        return expression;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Expression rewriteInCondition(InExpression expression, SqlTableContext tableContext, SqlRewriteContext context) {
        JsonExtractResolution jsonResolution = resolveEncryptedJsonExtract(expression.getLeftExpression(), tableContext);
        if (jsonResolution != null) {
            if (!(expression.getRightExpression() instanceof ExpressionList<?>)) {
                throw new UnsupportedEncryptedOperationException(EncryptionErrorCode.INVALID_ENCRYPTED_QUERY_OPERAND,
                        "Unsupported JSON IN operand for encrypted fields.");
            }
            ExpressionList expressionList = (ExpressionList) expression.getRightExpression();
            for (int index = 0; index < expressionList.size(); index++) {
                Expression item = (Expression) expressionList.get(index);
                expressionList.set(index, rewriteJsonAssistedOperand(
                        jsonResolution.pathRule(),
                        item,
                        context,
                        "Encrypted JSON IN condition must use prepared parameter, string literal, or CONCAT of them."
                ));
            }
            context.markChanged();
            return expression;
        }
        ColumnResolution resolution = resolveEncryptedColumn(expression.getLeftExpression(), tableContext);
        if (resolution == null) {
            if (expression.getRightExpression() instanceof Select) {
                selectRewriteDispatcher.rewrite((Select) expression.getRightExpression(),
                        context, ProjectionMode.NORMAL, tableContext);
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
            selectRewriteDispatcher.rewrite((Select) expression.getRightExpression(),
                    context, ProjectionMode.COMPARISON, tableContext);
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
            expression.setLeftExpression(columnBuilder.apply(resolution.column(), rule.column()));
            context.markChanged();
            return expression;
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

    private ColumnResolution resolveEncryptedColumn(Expression expression, SqlTableContext tableContext) {
        if (expression instanceof Parenthesis) {
            return resolveEncryptedColumn(((Parenthesis) expression).getExpression(), tableContext);
        }
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

    private Expression rewriteJsonAssistedOperand(EncryptJsonPathRule pathRule,
                                                  Expression operand,
                                                  SqlRewriteContext context,
                                                  String unsupportedMessage) {
        QueryOperand queryOperand = operandSupport.readComposableQueryOperand(operand, context,
                EncryptionErrorCode.INVALID_ENCRYPTED_QUERY_OPERAND, unsupportedMessage);
        return operandSupport.buildComposableQueryExpression(queryOperand, context,
                valueTransformer.transformJsonAssisted(pathRule, queryOperand.value()),
                MaskingMode.HASH);
    }

    private JsonExtractResolution resolveEncryptedJsonExtract(Expression expression, SqlTableContext tableContext) {
        if (!(expression instanceof Function)) {
            return null;
        }
        Function function = (Function) expression;
        if (!"json_extract".equalsIgnoreCase(function.getName())
                || function.getParameters() == null
                || function.getParameters().size() != 2) {
            return null;
        }
        Object first = function.getParameters().get(0);
        Object second = function.getParameters().get(1);
        if (!(first instanceof Column) || !(second instanceof StringValue)) {
            throw new UnsupportedEncryptedOperationException(EncryptionErrorCode.UNSUPPORTED_ENCRYPTED_OPERATION,
                    "Encrypted JSON path query requires static JSON_EXTRACT(column, '$.path').");
        }
        Column column = (Column) first;
        String path = ((StringValue) second).getValue();
        EncryptJsonPathRule pathRule = tableContext.resolveJsonPath(column, path).orElse(null);
        return pathRule == null ? null : new JsonExtractResolution(function, pathRule);
    }

    private static final class JsonExtractResolution {

        private final Function function;
        private final EncryptJsonPathRule pathRule;

        private JsonExtractResolution(Function function, EncryptJsonPathRule pathRule) {
            this.function = function;
            this.pathRule = pathRule;
        }

        private Function function() {
            return function;
        }

        private EncryptJsonPathRule pathRule() {
            return pathRule;
        }
    }

    @FunctionalInterface
    interface SelectRewriteDispatcher {
        void rewrite(Select select,
                     SqlRewriteContext context,
                     ProjectionMode projectionMode,
                     SqlTableContext outerTableContext);
    }
}
