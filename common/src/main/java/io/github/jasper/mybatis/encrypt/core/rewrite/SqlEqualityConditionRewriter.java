package io.github.jasper.mybatis.encrypt.core.rewrite;

import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
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
        if (resolution.leftColumn()) {
            expression.setLeftExpression(columnBuilder.apply(resolution.column(), targetColumn));
            operandSupport.rewriteOperand(expression.getRightExpression(), context,
                    valueTransformer.transformAssisted(rule,
                            operandSupport.readOperandValue(expression.getRightExpression(), context)),
                    MaskingMode.HASH);
        } else {
            expression.setRightExpression(columnBuilder.apply(resolution.column(), targetColumn));
            operandSupport.rewriteOperand(expression.getLeftExpression(), context,
                    valueTransformer.transformAssisted(rule,
                            operandSupport.readOperandValue(expression.getLeftExpression(), context)),
                    MaskingMode.HASH);
        }
        context.markChanged();
        return expression;
    }

    private Expression rewriteSeparateTable(BinaryExpression expression,
                                            ColumnResolution resolution,
                                            SqlRewriteContext context,
                                            EncryptColumnRule rule) {
        Expression operand = resolution.leftColumn() ? expression.getRightExpression() : expression.getLeftExpression();
        QueryOperand queryOperand = operandSupport.readComposableQueryOperand(operand, context,
                io.github.jasper.mybatis.encrypt.exception.EncryptionErrorCode.INVALID_ENCRYPTED_QUERY_OPERAND,
                "Separate-table encrypted query must use prepared parameter, string literal, or CONCAT of them.");
        String targetColumn = assistedQueryColumnProvider.apply(rule, "equality query");
        String transformed = valueTransformer.transformAssisted(rule, queryOperand.value());
        context.markChanged();
        Expression valueExpression = operandSupport.buildComposableQueryExpression(queryOperand, context,
                transformed, MaskingMode.HASH);
        return separateTableExistsConditionBuilder.buildExistsCondition(resolution.column(), rule,
                separateTableExistsConditionBuilder.buildEqualityPredicate(targetColumn, valueExpression));
    }
}
