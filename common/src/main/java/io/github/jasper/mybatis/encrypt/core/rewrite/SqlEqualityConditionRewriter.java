package io.github.jasper.mybatis.encrypt.core.rewrite;

import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import io.github.jasper.mybatis.encrypt.exception.EncryptionErrorCode;
import io.github.jasper.mybatis.encrypt.exception.UnsupportedEncryptedOperationException;
import io.github.jasper.mybatis.encrypt.util.NameUtils;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Column;

import java.util.function.BiFunction;

/**
 * 等值条件改写器。
 */
final class SqlEqualityConditionRewriter {

    private final EncryptionValueTransformer valueTransformer;
    private final BiFunction<Column, String, Column> columnBuilder;
    private final BiFunction<EncryptColumnRule, String, String> assistedQueryColumnProvider;
    private final SqlConditionOperandSupport operandSupport;
    private final SqlSeparateTableExistsConditionBuilder separateTableExistsConditionBuilder;

    SqlEqualityConditionRewriter(EncryptionValueTransformer valueTransformer,
                                 BiFunction<Column, String, Column> columnBuilder,
                                 BiFunction<EncryptColumnRule, String, String> assistedQueryColumnProvider,
                                 SqlConditionOperandSupport operandSupport,
                                 SqlSeparateTableExistsConditionBuilder separateTableExistsConditionBuilder) {
        this.valueTransformer = valueTransformer;
        this.columnBuilder = columnBuilder;
        this.assistedQueryColumnProvider = assistedQueryColumnProvider;
        this.operandSupport = operandSupport;
        this.separateTableExistsConditionBuilder = separateTableExistsConditionBuilder;
    }

    Expression rewrite(BinaryExpression expression,
                       ColumnResolution resolution,
                       SqlRewriteContext context) {
        EncryptColumnRule rule = resolution.rule();
        if (rule.isStoredInSeparateTable()) {
            return rewriteSeparateTable(expression, resolution, context, rule);
        }
        String targetColumn = assistedQueryColumnProvider.apply(rule, "equality query");
        Expression operand = resolution.leftColumn() ? expression.getRightExpression() : expression.getLeftExpression();
        Expression rewrittenOperand = rewriteAssistedOperand(rule, operand, context,
                "Encrypted equality condition must use prepared parameter, string literal, or CONCAT of them.");
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

    Expression rewriteColumnComparison(BinaryExpression expression,
                                       ColumnResolution left,
                                       ColumnResolution right,
                                       SqlRewriteContext context) {
        validateComparableAssistedAlgorithm(left.rule(), right.rule());
        expression.setLeftExpression(columnBuilder.apply(left.column(), comparisonColumn(left.rule())));
        expression.setRightExpression(columnBuilder.apply(right.column(), comparisonColumn(right.rule())));
        context.markChanged();
        return expression;
    }

    private Expression rewriteSeparateTable(BinaryExpression expression,
                                            ColumnResolution resolution,
                                            SqlRewriteContext context,
                                            EncryptColumnRule rule) {
        Expression operand = resolution.leftColumn() ? expression.getRightExpression() : expression.getLeftExpression();
        QueryOperand queryOperand = operandSupport.readComposableQueryOperand(operand, context,
                EncryptionErrorCode.INVALID_ENCRYPTED_QUERY_OPERAND,
                "Separate-table encrypted query must use prepared parameter, string literal, or CONCAT of them.");
        String targetColumn = assistedQueryColumnProvider.apply(rule, "equality query");
        String transformed = valueTransformer.transformAssisted(rule, queryOperand.value());
        context.markChanged();
        Expression valueExpression = operandSupport.buildComposableQueryExpression(queryOperand, context,
                transformed, MaskingMode.HASH);
        return separateTableExistsConditionBuilder.buildExistsCondition(resolution.column(), rule,
                separateTableExistsConditionBuilder.buildEqualityPredicate(targetColumn, valueExpression));
    }

    private Expression rewriteAssistedOperand(EncryptColumnRule rule,
                                              Expression operand,
                                              SqlRewriteContext context,
                                              String unsupportedMessage) {
        QueryOperand queryOperand = operandSupport.readComposableQueryOperand(operand, context,
                EncryptionErrorCode.INVALID_ENCRYPTED_QUERY_OPERAND, unsupportedMessage);
        // 同表等值查询也统一走可组合表达式改写，避免 CONCAT/固定值仍停留在明文字面量上。
        return operandSupport.buildComposableQueryExpression(queryOperand, context,
                valueTransformer.transformAssisted(rule, queryOperand.value()),
                MaskingMode.HASH);
    }

    private String comparisonColumn(EncryptColumnRule rule) {
        return rule.isStoredInSeparateTable()
                ? rule.column()
                : assistedQueryColumnProvider.apply(rule, "equality column comparison");
    }

    private void validateComparableAssistedAlgorithm(EncryptColumnRule left, EncryptColumnRule right) {
        String leftAlgorithm = NameUtils.normalizeIdentifier(left.assistedQueryAlgorithm());
        String rightAlgorithm = NameUtils.normalizeIdentifier(right.assistedQueryAlgorithm());
        if (leftAlgorithm != null && leftAlgorithm.equals(rightAlgorithm)) {
            return;
        }
        throw new UnsupportedEncryptedOperationException(EncryptionErrorCode.UNSUPPORTED_ENCRYPTED_OPERATION,
                "Encrypted column equality comparison requires the same assisted query algorithm.");
    }
}
