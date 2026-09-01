package io.github.jasper.mybatis.encrypt.core.rewrite;

import java.util.ArrayList;
import java.util.List;

/**
 * 可组合查询操作数。
 */
final class QueryOperand {

    private final Object value;
    private final List<Integer> parameterIndexes;

    QueryOperand(Object value, List<Integer> parameterIndexes) {
        this.value = value;
        this.parameterIndexes = parameterIndexes;
    }

    static QueryOperand parameter(Object value, int parameterIndex) {
        List<Integer> parameterIndexes = new ArrayList<Integer>(1);
        parameterIndexes.add(parameterIndex);
        return new QueryOperand(value, parameterIndexes);
    }

    static QueryOperand literal(Object value) {
        return new QueryOperand(value, new ArrayList<Integer>());
    }

    Object value() {
        return value;
    }

    List<Integer> parameterIndexes() {
        return parameterIndexes;
    }

    boolean parameterized() {
        return !parameterIndexes.isEmpty();
    }
}
