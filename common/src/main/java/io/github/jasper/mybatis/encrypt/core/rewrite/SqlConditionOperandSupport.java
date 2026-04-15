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

    Object readOperandValue(Expression expression, SqlRewriteContext context) {
        if (expression instanceof JdbcParameter) {
            int parameterIndex = context.consumeOriginal();
            return context.originalValue(parameterIndex);
        }
        if (expression instanceof StringValue) {
            return ((StringValue) expression).getValue();
        }
        if (expression instanceof LongValue) {
            return ((LongValue) expression).getStringValue();
        }
        return null;
    }

    void rewriteOperand(Expression expression, SqlRewriteContext context, String transformedValue, MaskingMode maskingMode) {
        if (expression instanceof JdbcParameter) {
            context.replaceLastConsumed(transformedValue, maskingMode);
            return;
        }
        if (expression instanceof StringValue) {
            ((StringValue) expression).setValue(transformedValue);
            return;
        }
        if (expression instanceof NullValue) {
            return;
        }
        throw new UnsupportedEncryptedOperationException(EncryptionErrorCode.INVALID_ENCRYPTED_QUERY_OPERAND,
                "Encrypted query condition must use prepared parameter or string literal.");
    }

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
            QueryOperand assistedCandidate = null;
            boolean multipleAssistedCandidates = false;
            for (Expression item : function.getParameters()) {
                QueryOperand part = readComposableQueryOperand(item, context, errorCode, unsupportedMessage);
                parameterIndexes.addAll(part.parameterIndexes());
                if (part.value() == null) {
                    return QueryOperand.none(null, parameterIndexes);
                }
                builder.append(part.value());
                if (part.hasAssistedCandidate() && part.assistedCandidateParameterized()) {
                    if (assistedCandidate != null) {
                        multipleAssistedCandidates = true;
                    } else {
                        assistedCandidate = part;
                    }
                }
            }
            if (multipleAssistedCandidates || assistedCandidate == null) {
                return QueryOperand.none(builder.toString(), parameterIndexes);
            }
            return new QueryOperand(builder.toString(), parameterIndexes,
                    assistedCandidate.assistedCandidateValue(), true);
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
