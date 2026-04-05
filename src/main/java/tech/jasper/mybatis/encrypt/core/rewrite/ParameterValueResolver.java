package tech.jasper.mybatis.encrypt.core.rewrite;

import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.property.PropertyTokenizer;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.TypeHandlerRegistry;

/**
 * MyBatis 参数值解析器。
 *
 * <p>用于根据 {@link ParameterMapping} 从参数对象、附加参数或简单类型中提取真实值，
 * 供 SQL 改写时计算密文、辅助列值和 LIKE 列值使用。</p>
 */
public class ParameterValueResolver {

    /**
     * 解析单个参数映射对应的运行时值。
     *
     * @param configuration MyBatis 配置
     * @param boundSql 原始 BoundSql
     * @param parameterObject 方法入参对象
     * @param parameterMapping 当前参数映射
     * @return 实际参数值
     */
    public Object resolve(Configuration configuration,
                          BoundSql boundSql,
                          Object parameterObject,
                          ParameterMapping parameterMapping) {
        if (parameterMapping == null) {
            return null;
        }
        String property = parameterMapping.getProperty();
        if (property == null) {
            return parameterObject;
        }
        String root = new PropertyTokenizer(property).getName();
        if (boundSql.hasAdditionalParameter(root)) {
            return boundSql.getAdditionalParameter(property);
        }
        if (parameterObject == null) {
            return null;
        }
        TypeHandlerRegistry registry = configuration.getTypeHandlerRegistry();
        if (registry.hasTypeHandler(parameterObject.getClass())) {
            return parameterObject;
        }
        MetaObject metaObject = configuration.newMetaObject(parameterObject);
        return metaObject.hasGetter(property) ? metaObject.getValue(property) : null;
    }
}
