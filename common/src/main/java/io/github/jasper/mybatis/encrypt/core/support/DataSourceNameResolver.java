package io.github.jasper.mybatis.encrypt.core.support;

import org.apache.ibatis.mapping.MappedStatement;

import javax.sql.DataSource;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 按 Spring bean 名称解析当前执行使用的数据源。
 */
public class DataSourceNameResolver {

    private final Map<DataSource, String> namesByIdentity =
            new IdentityHashMap<DataSource, String>();
    private final String defaultDataSourceName;

    /**
     * 基于命名数据源集合创建解析器。
     *
     * @param namedDataSources bean 名称与数据源映射
     */
    public DataSourceNameResolver(Map<String, DataSource> namedDataSources) {
        String firstName = null;
        if (namedDataSources != null) {
            for (Map.Entry<String, DataSource> entry : new LinkedHashMap<String, DataSource>(namedDataSources).entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                if (firstName == null) {
                    firstName = entry.getKey();
                }
                namesByIdentity.put(entry.getValue(), entry.getKey());
            }
        }
        this.defaultDataSourceName = firstName;
    }

    /**
     * 根据 MyBatis statement 解析数据源名称。
     *
     * @param mappedStatement 当前 statement
     * @return 数据源名称；未知时返回默认名称
     */
    public String resolve(MappedStatement mappedStatement) {
        if (mappedStatement == null
                || mappedStatement.getConfiguration() == null
                || mappedStatement.getConfiguration().getEnvironment() == null) {
            return defaultDataSourceName;
        }
        return resolve(mappedStatement.getConfiguration().getEnvironment().getDataSource());
    }

    /**
     * 根据数据源实例解析名称。
     *
     * @param dataSource 数据源实例
     * @return 数据源名称；未知时返回默认名称
     */
    public String resolve(DataSource dataSource) {
        if (dataSource == null) {
            return defaultDataSourceName;
        }
        String resolved = namesByIdentity.get(dataSource);
        return resolved != null ? resolved : defaultDataSourceName;
    }

    /**
     * 返回默认数据源名称。
     *
     * @return 默认数据源名称
     */
    public String getDefaultDataSourceName() {
        return defaultDataSourceName;
    }
}
