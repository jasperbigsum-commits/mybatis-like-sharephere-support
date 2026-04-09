package io.github.jasper.mybatis.encrypt.core.rewrite;

import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * SQL 改写结果。
 *
 * <p>封装改写后的 SQL、参数映射和脱敏后的日志参数。通过 {@link #applyTo(BoundSql)}
 * 可以把改写结果回填到 MyBatis 的运行时对象中。</p>
 */
public class RewriteResult {

    private static final RewriteResult UNCHANGED =
            new RewriteResult(false, null, Collections.emptyList(), Collections.emptyMap(), null);

    private final boolean changed;
    private final String sql;
    private final List<ParameterMapping> parameterMappings;
    private final Map<String, MaskedValue> maskedParameters;
    private final String maskedSql;

    /**
     * 创建一次 SQL 改写结果。
     *
     * @param changed 是否发生改写
     * @param sql 改写后的 SQL
     * @param parameterMappings 改写后的参数映射
     * @param maskedParameters 脱敏后的参数日志值
     * @param maskedSql 带参数脱敏片段的日志 SQL
     */
    public RewriteResult(boolean changed,
                         String sql,
                         List<ParameterMapping> parameterMappings,
                         Map<String, MaskedValue> maskedParameters,
                         String maskedSql) {
        this.changed = changed;
        this.sql = sql;
        this.parameterMappings = parameterMappings;
        this.maskedParameters = maskedParameters;
        this.maskedSql = maskedSql;
    }

    /**
     * 返回未改写的共享结果实例。
     *
     * @return 未改写结果
     */
    public static RewriteResult unchanged() {
        return UNCHANGED;
    }

    /**
     * 判断本次 SQL 是否被改写。
     *
     * @return 发生改写时返回 {@code true}
     */
    public boolean changed() {
        return changed;
    }

    /**
     * 返回改写后的 SQL。
     *
     * @return 改写后的 SQL
     */
    public String sql() {
        return sql;
    }

    /**
     * 返回脱敏参数集合。
     *
     * @return 参数脱敏日志值
     */
    public Map<String, MaskedValue> maskedParameters() {
        return maskedParameters;
    }

    /**
     * 返回可写入日志的脱敏 SQL。
     *
     * @return 脱敏后的 SQL
     */
    public String maskedSql() {
        return maskedSql;
    }

    /**
     * 将改写结果应用到 MyBatis 当前的 {@link BoundSql}。
     *
     * @param boundSql 需要被替换内容的 BoundSql
     */
    public void applyTo(BoundSql boundSql) {
        if (!changed) {
            return;
        }
        MetaObject metaObject = SystemMetaObject.forObject(boundSql);
        metaObject.setValue("sql", sql);
        metaObject.setValue("parameterMappings", parameterMappings);
    }
}
