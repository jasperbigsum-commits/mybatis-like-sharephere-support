package io.github.jasper.mybatis.encrypt.migration;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;

import javax.sql.DataSource;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 默认全局迁移任务工厂，按数据源名称路由到具体的 JDBC 迁移工厂。
 */
public class DefaultGlobalMigrationTaskFactory implements GlobalMigrationTaskFactory {

    private final Map<String, MigrationTaskFactory> factories;

    /**
     * 创建全局迁移任务工厂。
     *
     * @param dataSources 数据源集合
     * @param metadataRegistry 元数据注册中心
     * @param algorithmRegistry 算法注册中心
     * @param properties 插件配置
     * @param stateStore 状态存储器
     * @param confirmationPolicy 确认策略
     */
    public DefaultGlobalMigrationTaskFactory(Map<String, DataSource> dataSources,
                                             EncryptMetadataRegistry metadataRegistry,
                                             AlgorithmRegistry algorithmRegistry,
                                             DatabaseEncryptionProperties properties,
                                             MigrationStateStore stateStore,
                                             MigrationConfirmationPolicy confirmationPolicy) {
        Map<String, MigrationTaskFactory> routingFactories = new LinkedHashMap<String, MigrationTaskFactory>();
        for (Map.Entry<String, DataSource> entry : dataSources.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            routingFactories.put(entry.getKey(), new DefaultMigrationTaskFactory(
                    entry.getValue(),
                    entry.getKey(),
                    metadataRegistry,
                    algorithmRegistry,
                    properties,
                    stateStore,
                    confirmationPolicy));
        }
        this.factories = Collections.unmodifiableMap(routingFactories);
    }

    @Override
    public Set<String> getDataSourceNames() {
        return factories.keySet();
    }

    @Override
    public MigrationTaskFactory forDataSource(String dataSourceName) {
        MigrationTaskFactory factory = factories.get(dataSourceName);
        if (factory == null) {
            throw new IllegalArgumentException("Unknown migration datasource: " + dataSourceName
                    + ", available=" + factories.keySet());
        }
        return factory;
    }
}
