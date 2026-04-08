package io.github.jasper.mybatis.encrypt.core.rewrite;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.sf.jsqlparser.expression.JdbcParameter;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.session.Configuration;

/**
 * 一次 SQL 改写过程中的可变上下文。
 *
 * <p>它同时维护当前已消费到第几个原始参数、改写后的参数映射列表以及新增附加参数。
 * 排查参数个数不一致、顺序错位或 `BoundSql.additionalParameter` 被覆盖时，应优先检查这里。</p>
 */
final class SqlRewriteContext {

    private final Configuration configuration;
    private final BoundSql boundSql;
    private final ParameterValueResolver parameterValueResolver;
    private final List<ParameterMapping> parameterMappings;
    private final Map<String, MaskedValue> maskedParameters = new LinkedHashMap<>();
    private int currentParameterIndex;
    private int generatedIndex;
    private boolean changed;

    SqlRewriteContext(Configuration configuration, BoundSql boundSql, ParameterValueResolver parameterValueResolver) {
        this.configuration = configuration;
        this.boundSql = boundSql;
        this.parameterValueResolver = parameterValueResolver;
        this.parameterMappings = new ArrayList<>(boundSql.getParameterMappings());
    }

    int consumeOriginal() {
        return currentParameterIndex++;
    }

    Object originalValue(int index) {
        if (index < 0 || index >= parameterMappings.size()) {
            return null;
        }
        return parameterValueResolver.resolve(configuration, boundSql, boundSql.getParameterObject(),
                parameterMappings.get(index));
    }

    void replaceLastConsumed(Object value, MaskingMode maskingMode) {
        replaceParameter(currentParameterIndex - 1, value, maskingMode);
    }

    /**
     * 在当前位置插入一个新合成参数。
     *
     * <p>这主要服务于辅助列和 LIKE 列，因为它们会在原 SQL 的参数序列中新增占位符。
     * 如果不是按当前位置插入，而是简单追加到末尾，JDBC 绑定顺序就会和 SQL 占位符顺序错开。</p>
     */
    JdbcParameter insertSynthetic(Object value, MaskingMode maskingMode) {
        String property = nextSyntheticName();
        parameterMappings.add(currentParameterIndex, new ParameterMapping.Builder(configuration, property,
                value == null ? String.class : value.getClass()).build());
        boundSql.setAdditionalParameter(property, value);
        maskedParameters.put(property, mask(maskingMode, value));
        currentParameterIndex++;
        changed = true;
        return new JdbcParameter();
    }

    /**
     * 用新合成属性替换原有参数槽位。
     *
     * <p>这里不能复用原属性名，否则 MyBatis 在运行时仍可能回到业务参数对象中取同名值，
     * 导致已经改写完成的附加参数被原始明文覆盖。</p>
     */
    void replaceParameter(int parameterIndex, Object value, MaskingMode maskingMode) {
        if (parameterIndex < 0 || parameterIndex >= parameterMappings.size()) {
            return;
        }
        ParameterMapping original = parameterMappings.get(parameterIndex);
        String property = nextSyntheticName();
        ParameterMapping rewritten = new ParameterMapping.Builder(configuration, property,
                value == null ? String.class : value.getClass())
                .jdbcType(original.getJdbcType())
                .build();
        parameterMappings.set(parameterIndex, rewritten);
        boundSql.setAdditionalParameter(property, value);
        maskedParameters.put(property, mask(maskingMode, value));
        changed = true;
    }

    void removeParameter(int parameterIndex) {
        if (parameterIndex < 0 || parameterIndex >= parameterMappings.size()) {
            return;
        }
        parameterMappings.remove(parameterIndex);
        if (parameterIndex < currentParameterIndex) {
            currentParameterIndex--;
        }
        changed = true;
    }

    List<ParameterMapping> parameterMappings() {
        return parameterMappings;
    }

    Map<String, MaskedValue> maskedParameters() {
        return maskedParameters;
    }

    boolean changed() {
        return changed;
    }

    void markChanged() {
        changed = true;
    }

    private String nextSyntheticName() {
        generatedIndex++;
        return "__encrypt_generated_" + generatedIndex;
    }

    private MaskedValue mask(MaskingMode maskingMode, Object value) {
        if (value == null) {
            return new MaskedValue(maskingMode.name(), "<null>");
        }
        if (maskingMode == MaskingMode.HASH) {
            return new MaskedValue(maskingMode.name(), String.valueOf(value));
        }
        return new MaskedValue(maskingMode.name(), "***");
    }
}
