package io.github.jasper.mybatis.encrypt.core.rewrite;

import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import io.github.jasper.mybatis.encrypt.exception.EncryptionErrorCode;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.relational.LikeExpression;
import net.sf.jsqlparser.schema.Column;

import java.util.function.BiFunction;

/**
 * LIKE 条件改写器。
 */
final class SqlLikeConditionRewriter {

    private final EncryptionValueTransformer valueTransformer;
    private final BiFunction<Column, String, Column> columnBuilder;
    private final BiFunction<EncryptColumnRule, String, String> assistedQueryColumnProvider;
    private final BiFunction<EncryptColumnRule, String, String> likeQueryColumnProvider;
    private final SqlConditionOperandSupport operandSupport;
    private final SqlSeparateTableExistsConditionBuilder separateTableExistsConditionBuilder;

    SqlLikeConditionRewriter(EncryptionValueTransformer valueTransformer,
                             BiFunction<Column, String, Column> columnBuilder,
                             BiFunction<EncryptColumnRule, String, String> assistedQueryColumnProvider,
                             BiFunction<EncryptColumnRule, String, String> likeQueryColumnProvider,
                             SqlConditionOperandSupport operandSupport,
                             SqlSeparateTableExistsConditionBuilder separateTableExistsConditionBuilder) {
        this.valueTransformer = valueTransformer;
        this.columnBuilder = columnBuilder;
        this.assistedQueryColumnProvider = assistedQueryColumnProvider;
        this.likeQueryColumnProvider = likeQueryColumnProvider;
        this.operandSupport = operandSupport;
        this.separateTableExistsConditionBuilder = separateTableExistsConditionBuilder;
    }

    Expression rewrite(LikeExpression expression,
                       ColumnResolution resolution,
                       SqlRewriteContext context) {
        EncryptColumnRule rule = resolution.rule();
        QueryOperand queryOperand = operandSupport.readComposableQueryOperand(expression.getRightExpression(), context,
                EncryptionErrorCode.INVALID_ENCRYPTED_QUERY_OPERAND,
                "Encrypted LIKE condition must use prepared parameter, string literal, or CONCAT of them.");
        Expression likeValueExpression = operandSupport.buildComposableQueryExpression(queryOperand, context,
                valueTransformer.transformLike(rule, queryOperand.value()), MaskingMode.MASKED);
        Expression assistedFallbackExpression = buildAssistedFallbackExpression(expression, rule, queryOperand, context);
        context.markChanged();
        if (rule.isStoredInSeparateTable()) {
            return rewriteSeparateTable(resolution, rule, likeValueExpression, assistedFallbackExpression);
        }
        expression.setLeftExpression(columnBuilder.apply(resolution.column(),
                likeQueryColumnProvider.apply(rule, "LIKE query")));
        expression.setRightExpression(likeValueExpression);
        if (assistedFallbackExpression == null) {
            return expression;
        }
        return separateTableExistsConditionBuilder.wrapWithParenthesizedOr(expression,
                separateTableExistsConditionBuilder.buildEqualityPredicate(
                        columnBuilder.apply(resolution.column(),
                                assistedQueryColumnProvider.apply(rule, "LIKE assisted fallback query")),
                        assistedFallbackExpression
                ));
    }

    private Expression rewriteSeparateTable(ColumnResolution resolution,
                                            EncryptColumnRule rule,
                                            Expression likeValueExpression,
                                            Expression assistedFallbackExpression) {
        Expression likePredicate = separateTableExistsConditionBuilder.buildLikePredicate(
                likeQueryColumnProvider.apply(rule, "LIKE query"), likeValueExpression);
        Expression valuePredicate = likePredicate;
        if (assistedFallbackExpression != null) {
            valuePredicate = separateTableExistsConditionBuilder.wrapWithParenthesizedOr(likePredicate,
                    separateTableExistsConditionBuilder.buildEqualityPredicate(
                            assistedQueryColumnProvider.apply(rule, "LIKE assisted fallback query"),
                            assistedFallbackExpression
                    ));
        }
        return separateTableExistsConditionBuilder.buildExistsCondition(resolution.column(), rule, valuePredicate);
    }

    private Expression buildAssistedFallbackExpression(LikeExpression expression,
                                                       EncryptColumnRule rule,
                                                       QueryOperand queryOperand,
                                                       SqlRewriteContext context) {
        if (expression.isNot() || !rule.hasAssistedQueryColumn() || !queryOperand.hasAssistedCandidate()) {
            return null;
        }
        String transformed = valueTransformer.transformAssisted(rule, queryOperand.assistedCandidateValue());
        if (transformed == null) {
            return null;
        }
        if (!queryOperand.assistedCandidateParameterized()) {
            return new StringValue(transformed);
        }
        return context.insertSynthetic(transformed, MaskingMode.HASH);
    }
}
