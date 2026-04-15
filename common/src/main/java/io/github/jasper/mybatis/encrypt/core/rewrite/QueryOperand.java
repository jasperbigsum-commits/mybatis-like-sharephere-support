package io.github.jasper.mybatis.encrypt.core.rewrite;

import java.util.ArrayList;
import java.util.List;

/**
 * 可组合查询操作数。
 */
final class QueryOperand {

    private final Object value;
    private final List<Integer> parameterIndexes;
    private final Object assistedCandidateValue;
    private final boolean assistedCandidateParameterized;

    QueryOperand(Object value,
                 List<Integer> parameterIndexes,
                 Object assistedCandidateValue,
                 boolean assistedCandidateParameterized) {
        this.value = value;
        this.parameterIndexes = parameterIndexes;
        this.assistedCandidateValue = assistedCandidateValue;
        this.assistedCandidateParameterized = assistedCandidateParameterized;
    }

    static QueryOperand parameter(Object value, int parameterIndex) {
        List<Integer> parameterIndexes = new ArrayList<Integer>(1);
        parameterIndexes.add(parameterIndex);
        return new QueryOperand(value, parameterIndexes, value, true);
    }

    static QueryOperand literal(Object value) {
        return new QueryOperand(value, new ArrayList<Integer>(), value, false);
    }

    static QueryOperand none(Object value, List<Integer> parameterIndexes) {
        return new QueryOperand(value, parameterIndexes, null, false);
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

    boolean hasAssistedCandidate() {
        return assistedCandidateValue != null;
    }

    Object assistedCandidateValue() {
        return assistedCandidateValue;
    }

    boolean assistedCandidateParameterized() {
        return assistedCandidateParameterized;
    }
}
