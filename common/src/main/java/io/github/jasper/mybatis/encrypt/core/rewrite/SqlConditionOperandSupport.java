package io.github.jasper.mybatis.encrypt.core.rewrite;

import io.github.jasper.mybatis.encrypt.exception.EncryptionErrorCode;
import io.github.jasper.mybatis.encrypt.exception.UnsupportedEncryptedOperationException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.StringValue;

import java.util.ArrayList;
import java.util.List;

/**
 * 条件改写中的通用操作数处理。
 */
final class SqlConditionOperandSupport {

    QueryOperand readComposableQueryOperand(Expression expression,
                                            SqlRewriteContext context,
                                            EncryptionErrorCode errorCode,
                                            String unsupportedMessage) {
        if (expression instanceof JdbcParameter) {
            int parameterIndex = context.consumeOriginal();
            return QueryOperand.parameter(context.originalValue(parameterIndex), parameterIndex);
        }
        if (expression instanceof StringValue) {
            return QueryOperand.literal(((StringValue) expression).getValue());
        }
        if (expression instanceof LongValue) {
            return QueryOperand.literal(((LongValue) expression).getStringValue());
        }
        if (expression instanceof NullValue) {
            return QueryOperand.literal(null);
        }
        if (expression instanceof Parenthesis) {
            return readComposableQueryOperand(((Parenthesis) expression).getExpression(), context, errorCode, unsupportedMessage);
        }
        if (expression instanceof Function && ((Function) expression).getParameters() != null) {
            Function function = (Function) expression;
            if (!"concat".equalsIgnoreCase(function.getName())) {
                throw new UnsupportedEncryptedOperationException(errorCode, unsupportedMessage);
            }
            StringBuilder builder = new StringBuilder();
            List<Integer> parameterIndexes = new ArrayList<Integer>();
            for (Expression item : function.getParameters()) {
                QueryOperand part = readComposableQueryOperand(item, context, errorCode, unsupportedMessage);
                parameterIndexes.addAll(part.parameterIndexes());
                if (part.value() == null) {
                    return new QueryOperand(null, parameterIndexes);
                }
                builder.append(part.value());
            }
            // CONCAT 的最终值一旦可确定，就交给上层按具体语义决定是否还能退化为 hash 精确匹配。
            return new QueryOperand(builder.toString(), parameterIndexes);
        }
        throw new UnsupportedEncryptedOperationException(errorCode, unsupportedMessage);
    }

    Expression buildComposableQueryExpression(QueryOperand operand,
                                              SqlRewriteContext context,
                                              String transformed,
                                              MaskingMode maskingMode) {
        if (!operand.parameterized()) {
            return transformed == null ? new NullValue() : new StringValue(transformed);
        }
        List<Integer> parameterIndexes = operand.parameterIndexes();
        for (int index = parameterIndexes.size() - 1; index >= 0; index--) {
            context.removeParameter(parameterIndexes.get(index));
        }
        return context.insertSynthetic(transformed, maskingMode);
    }
}
