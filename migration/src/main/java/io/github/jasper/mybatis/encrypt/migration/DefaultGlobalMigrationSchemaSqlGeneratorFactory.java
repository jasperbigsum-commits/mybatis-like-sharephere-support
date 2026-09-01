package io.github.jasper.mybatis.encrypt.migration;

import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;

import javax.sql.DataSource;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 默认全局 DDL 生成工厂，按数据源名称路由到具体的 schema DDL 生成器。
 */
public class DefaultGlobalMigrationSchemaSqlGeneratorFactory implements GlobalMigrationSchemaSqlGeneratorFactory {

    private final Map<String, MigrationSchemaSqlGenerator> generators;

    /**
     * 使用默认长度策略创建全局 DDL 生成工厂。
     *
     * @param dataSources 数据源集合
     * @param metadataRegistry 元数据注册中心
     * @param properties 插件配置
     */
    public DefaultGlobalMigrationSchemaSqlGeneratorFactory(Map<String, DataSource> dataSources,
                                                           EncryptMetadataRegistry metadataRegistry,
                                                           DatabaseEncryptionProperties properties) {
        this(dataSources, metadataRegistry, properties, new MigrationSchemaSqlGenerator.SizingOptions());
    }

    /**
     * 使用自定义长度策略创建全局 DDL 生成工厂。
     *
     * @param dataSources 数据源集合
     * @param metadataRegistry 元数据注册中心
     * @param properties 插件配置
     * @param sizingOptions 字段长度策略
     */
    public DefaultGlobalMigrationSchemaSqlGeneratorFactory(Map<String, DataSource> dataSources,
                                                           EncryptMetadataRegistry metadataRegistry,
                                                           DatabaseEncryptionProperties properties,
                                                           MigrationSchemaSqlGenerator.SizingOptions sizingOptions) {
        Map<String, MigrationSchemaSqlGenerator> routingGenerators =
                new LinkedHashMap<String, MigrationSchemaSqlGenerator>();
        for (Map.Entry<String, DataSource> entry : dataSources.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            routingGenerators.put(entry.getKey(), new MigrationSchemaSqlGenerator(
                    entry.getValue(),
                    entry.getKey(),
                    metadataRegistry,
                    properties,
                    sizingOptions));
        }
        this.generators = Collections.unmodifiableMap(routingGenerators);
    }

    @Override
    public Set<String> getDataSourceNames() {
        return generators.keySet();
    }

    @Override
    public MigrationSchemaSqlGenerator forDataSource(String dataSourceName) {
        MigrationSchemaSqlGenerator generator = generators.get(dataSourceName);
        if (generator == null) {
            throw new IllegalArgumentException("Unknown migration schema datasource: " + dataSourceName
                    + ", available=" + generators.keySet());
        }
        return generator;
    }
}
