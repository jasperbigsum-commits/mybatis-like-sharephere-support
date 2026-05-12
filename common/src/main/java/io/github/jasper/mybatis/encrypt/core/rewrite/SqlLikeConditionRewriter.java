package io.github.jasper.mybatis.encrypt.core.rewrite;

import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import io.github.jasper.mybatis.encrypt.exception.EncryptionErrorCode;
import io.github.jasper.mybatis.encrypt.exception.UnsupportedEncryptedOperationException;
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
        AssistedCandidate assistedCandidate = deriveAssistedCandidate(queryOperand.value());
        if (rule.isStoredInSeparateTable() && canRewriteSeparateTableExactLike(expression, rule, assistedCandidate)) {
            context.markChanged();
            return buildDirectAssistedPredicate(resolution, rule, queryOperand, assistedCandidate.value(), context);
        }
        if (!rule.hasLikeQueryColumn()) {
            return rewriteLikeWithoutLikeColumn(expression, resolution, rule, queryOperand, assistedCandidate, context);
        }
        Expression likeValueExpression = operandSupport.buildComposableQueryExpression(queryOperand, context,
                valueTransformer.transformLike(rule, queryOperand.value()), MaskingMode.MASKED);
        Expression assistedFallbackExpression = buildAssistedFallbackExpression(
                expression, rule, queryOperand, assistedCandidate, context);
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
        Expression existsPredicate = separateTableExistsConditionBuilder.buildExistsCondition(
                resolution.column(), rule, likePredicate);
        if (assistedFallbackExpression == null) {
            return existsPredicate;
        }
        // 独立表主表字段已经保存 hash/ref，精确兜底无需再进入外表，直接比较主表字段即可。
        return separateTableExistsConditionBuilder.wrapWithParenthesizedOr(existsPredicate,
                separateTableExistsConditionBuilder.buildEqualityPredicate(
                        columnBuilder.apply(resolution.column(), rule.column()), assistedFallbackExpression));
    }

    private Expression buildAssistedFallbackExpression(LikeExpression expression,
                                                       EncryptColumnRule rule,
                                                       QueryOperand queryOperand,
                                                       AssistedCandidate assistedCandidate,
                                                       SqlRewriteContext context) {
        if (expression.isNot() || !rule.hasAssistedQueryColumn()) {
            return null;
        }
        if (assistedCandidate == null) {
            return null;
        }
        String transformed = valueTransformer.transformAssisted(rule, assistedCandidate.value());
        if (transformed == null) {
            return null;
        }
        if (!queryOperand.parameterized()) {
            return new StringValue(transformed);
        }
        return context.insertSynthetic(transformed, MaskingMode.HASH);
    }

    private boolean canRewriteSeparateTableExactLike(LikeExpression expression,
                                                     EncryptColumnRule rule,
                                                     AssistedCandidate assistedCandidate) {
        return !expression.isNot()
                && rule.hasAssistedQueryColumn()
                && assistedCandidate != null
                && assistedCandidate.exact();
    }

    private Expression buildDirectAssistedPredicate(ColumnResolution resolution,
                                                    EncryptColumnRule rule,
                                                    QueryOperand queryOperand,
                                                    String assistedCandidate,
                                                    SqlRewriteContext context) {
        Expression assistedExpression = operandSupport.buildComposableQueryExpression(queryOperand, context,
                valueTransformer.transformAssisted(rule, assistedCandidate), MaskingMode.HASH);
        return separateTableExistsConditionBuilder.buildEqualityPredicate(
                columnBuilder.apply(resolution.column(), rule.column()), assistedExpression);
    }

    private AssistedCandidate deriveAssistedCandidate(Object rawValue) {
        if (rawValue == null) {
            return null;
        }
        String value = String.valueOf(rawValue);
        if (value.isEmpty()) {
            return new AssistedCandidate(value, true);
        }
        int start = 0;
        int end = value.length();
        while (start < end && isLikeWildcard(value.charAt(start))) {
            start++;
        }
        while (end > start && isLikeWildcard(value.charAt(end - 1))) {
            end--;
        }
        if (start >= end) {
            return null;
        }
        for (int index = start; index < end; index++) {
            if (isLikeWildcard(value.charAt(index))) {
                return null;
            }
        }
        return new AssistedCandidate(value.substring(start, end), start == 0 && end == value.length());
    }

    private boolean isLikeWildcard(char ch) {
        return ch == '%' || ch == '_';
    }

    private static final class AssistedCandidate {

        private final String value;
        private final boolean exact;

        private AssistedCandidate(String value, boolean exact) {
            this.value = value;
            this.exact = exact;
        }

        private String value() {
            return value;
        }

        private boolean exact() {
            return exact;
        }
    }

    private Expression rewriteLikeWithoutLikeColumn(LikeExpression expression,
                                                    ColumnResolution resolution,
                                                    EncryptColumnRule rule,
                                                    QueryOperand queryOperand,
                                                    AssistedCandidate assistedCandidate,
                                                    SqlRewriteContext context) {
        String assistedValue = assistedCandidate == null ? String.valueOf(queryOperand.value()) : assistedCandidate.value();
        if (expression.isNot()) {
            throw new UnsupportedEncryptedOperationException(EncryptionErrorCode.MISSING_LIKE_QUERY_COLUMN,
                    "Encrypted NOT LIKE query requires likeQueryColumn.");
        }
        Expression assistedExpression = operandSupport.buildComposableQueryExpression(queryOperand, context,
                valueTransformer.transformAssisted(rule, assistedValue), MaskingMode.HASH);
        context.markChanged();
        if (rule.isStoredInSeparateTable()) {
            return separateTableExistsConditionBuilder.buildEqualityPredicate(
                    columnBuilder.apply(resolution.column(), rule.column()), assistedExpression);
        }
        return separateTableExistsConditionBuilder.buildEqualityPredicate(
                columnBuilder.apply(resolution.column(),
                        assistedQueryColumnProvider.apply(rule, "LIKE query fallback")),
                assistedExpression);
    }
}
