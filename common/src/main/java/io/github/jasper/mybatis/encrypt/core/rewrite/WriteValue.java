package io.github.jasper.mybatis.encrypt.core.rewrite;

import net.sf.jsqlparser.expression.Expression;

/**
 * 写路径单个字段的改写结果。
 *
 * <p>同时携带改写后的表达式、原始业务明文和值是否仍通过参数绑定，
 * 供主密文字段、辅助查询字段和 LIKE 字段复用同一份转换结果。</p>
 */
final class WriteValue {

    private final Expression expression;
    private final Object plainValue;
    private final boolean parameterized;

    WriteValue(Expression expression, Object plainValue, boolean parameterized) {
        this.expression = expression;
        this.plainValue = plainValue;
        this.parameterized = parameterized;
    }

    Expression expression() {
        return expression;
    }

    Object plainValue() {
        return plainValue;
    }

    boolean parameterized() {
        return parameterized;
    }
}
