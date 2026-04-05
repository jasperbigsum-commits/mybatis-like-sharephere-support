package tech.jasper.mybatis.encrypt.core.rewrite;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;

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

    public static RewriteResult unchanged() {
        return UNCHANGED;
    }

    public boolean changed() {
        return changed;
    }

    public String sql() {
        return sql;
    }

    public Map<String, MaskedValue> maskedParameters() {
        return maskedParameters;
    }

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
