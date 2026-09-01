package io.github.jasper.mybatis.encrypt.core.rewrite;

import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptJsonFieldRule;
import io.github.jasper.mybatis.encrypt.exception.EncryptionErrorCode;
import io.github.jasper.mybatis.encrypt.exception.UnsupportedEncryptedOperationException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.StringValue;

/**
 * 写路径表达式改写器。
 *
 * <p>负责处理 `INSERT/UPDATE SET` 中的业务明文读取、主密文转换、
 * 独立表引用替换以及辅助列/LIKE 列的 shadow 参数生成。</p>
 */
final class SqlWriteExpressionRewriter {

    private final EncryptionValueTransformer valueTransformer;
    private final SqlConditionRewriter conditionRewriter;
    private final EncryptJsonRuntimeSupport encryptJsonRuntimeSupport;

    SqlWriteExpressionRewriter(EncryptionValueTransformer valueTransformer,
                               SqlConditionRewriter conditionRewriter) {
        this.valueTransformer = valueTransformer;
        this.conditionRewriter = conditionRewriter;
        this.encryptJsonRuntimeSupport = new EncryptJsonRuntimeSupport();
    }

    WriteValue rewriteEncrypted(Expression expression, EncryptColumnRule rule, SqlRewriteContext context) {
        Object plainValue = readOperandValue(expression, context);
        String cipherValue = valueTransformer.transformCipher(rule, plainValue);
        if (expression instanceof JdbcParameter) {
            context.replaceLastConsumed(cipherValue, MaskingMode.MASKED);
            return new WriteValue(expression, plainValue, true);
        }
        if (expression instanceof StringValue) {
            StringValue stringValue = (StringValue) expression;
            stringValue.setValue(cipherValue);
            return new WriteValue(stringValue, plainValue, false);
        }
        if (expression instanceof LongValue) {
            return new WriteValue(new StringValue(cipherValue), plainValue, false);
        }
        if (expression instanceof NullValue) {
            return new WriteValue(expression, null, false);
        }
        throw new UnsupportedEncryptedOperationException(EncryptionErrorCode.INVALID_ENCRYPTED_WRITE_OPERAND,
                "Encrypted write only supports prepared parameters or string literals.");
    }

    Expression passthrough(Expression expression, SqlRewriteContext context) {
        conditionRewriter.consume(expression, context);
        return expression;
    }

    Expression rewriteSeparateTableReference(Expression expression, SqlRewriteContext context) {
        if (expression instanceof JdbcParameter) {
            int parameterIndex = context.consumeOriginal();
            Object referenceId = context.originalValue(parameterIndex);
            context.replaceParameter(parameterIndex, referenceId, MaskingMode.MASKED);
            return expression;
        }
        conditionRewriter.consume(expression, context);
        return expression;
    }

    Expression buildShadow(WriteValue writeValue, String value, MaskingMode maskingMode, SqlRewriteContext context) {
        if (value == null) {
            return new NullValue();
        }
        if (writeValue.parameterized()) {
            return context.insertSynthetic(value, maskingMode);
        }
        return new StringValue(value);
    }

    WriteValue rewriteEncryptJson(Expression expression,
                                  EncryptJsonFieldRule rule,
                                  SqlRewriteContext context,
                                  io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry algorithmRegistry) {
        Object jsonValue = readOperandValue(expression, context);
        String json = jsonValue == null ? null : String.valueOf(jsonValue);
        EncryptJsonWriteResult result = encryptJsonRuntimeSupport.rewriteJsonForWrite(json, rule, algorithmRegistry);
        context.appendPreparedJsonPathWrites(result.pathWrites());
        if (expression instanceof JdbcParameter) {
            context.replaceLastConsumed(result.rewrittenJson(), MaskingMode.HASH);
            return new WriteValue(expression, jsonValue, true);
        }
        if (expression instanceof StringValue) {
            StringValue stringValue = (StringValue) expression;
            stringValue.setValue(result.rewrittenJson());
            return new WriteValue(stringValue, jsonValue, false);
        }
        if (expression instanceof LongValue) {
            return new WriteValue(new StringValue(result.rewrittenJson()), jsonValue, false);
        }
        if (expression instanceof NullValue) {
            return new WriteValue(expression, null, false);
        }
        throw new UnsupportedEncryptedOperationException(EncryptionErrorCode.INVALID_ENCRYPTED_WRITE_OPERAND,
                "EncryptJsonField write only supports prepared parameters or string literals.");
    }

    private Object readOperandValue(Expression expression, SqlRewriteContext context) {
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
}
