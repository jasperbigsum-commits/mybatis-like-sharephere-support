package io.github.jasper.mybatis.encrypt.migration;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 面向多数据源的全局迁移任务工厂。
 */
public interface GlobalMigrationTaskFactory {

    /**
     * 返回已注册的数据源名称集合。
     *
     * @return 可用数据源名称
     */
    Set<String> getDataSourceNames();

    /**
     * 绑定到指定数据源的迁移工厂。
     *
     * @param dataSourceName 数据源名称
     * @return 绑定后的迁移工厂
     */
    MigrationTaskFactory forDataSource(String dataSourceName);

    /**
     * 直接按数据源名称创建迁移任务。
     *
     * @param dataSourceName 数据源名称
     * @param definition 迁移定义
     * @return 迁移任务
     */
    default MigrationTask create(String dataSourceName, EntityMigrationDefinition definition) {
        return forDataSource(dataSourceName).create(definition);
    }

    /**
     * 直接按数据源名称执行实体迁移。
     *
     * @param dataSourceName 数据源名称
     * @param entityType 实体类型
     * @param cursorColumns 游标列
     * @param builderCustomizer 可选 builder 自定义器
     * @return 迁移报告
     */
    default MigrationReport executeForEntity(String dataSourceName,
                                             Class<?> entityType,
                                             List<String> cursorColumns,
                                             Consumer<EntityMigrationDefinition.Builder> builderCustomizer) {
        return forDataSource(dataSourceName).executeForEntity(entityType, cursorColumns, builderCustomizer);
    }

    /**
     * 直接按数据源名称执行表迁移。
     *
     * @param dataSourceName 数据源名称
     * @param tableName 表名
     * @param cursorColumns 游标列
     * @param builderCustomizer 可选 builder 自定义器
     * @return 迁移报告
     */
    default MigrationReport executeForTable(String dataSourceName,
                                            String tableName,
                                            List<String> cursorColumns,
                                            Consumer<EntityMigrationDefinition.Builder> builderCustomizer) {
        return forDataSource(dataSourceName).executeForTable(tableName, cursorColumns, builderCustomizer);
    }

    /**
     * 直接按数据源名称执行实体迁移。
     *
     * @param dataSourceName 数据源名称
     * @param entityType 实体类型
     * @param cursorColumn 单列游标
     * @return 迁移报告
     */
    default MigrationReport executeForEntity(String dataSourceName, Class<?> entityType, String cursorColumn) {
        return executeForEntity(dataSourceName, entityType, Collections.singletonList(cursorColumn), null);
    }

    /**
     * 直接按数据源名称执行表迁移。
     *
     * @param dataSourceName 数据源名称
     * @param tableName 表名
     * @param cursorColumn 单列游标
     * @return 迁移报告
     */
    default MigrationReport executeForTable(String dataSourceName, String tableName, String cursorColumn) {
        return executeForTable(dataSourceName, tableName, Collections.singletonList(cursorColumn), null);
    }

    /**
     * 直接按数据源名称执行全部已注册物理表迁移。
     *
     * @param dataSourceName 数据源名称
     * @return 迁移报告列表
     */
    default List<MigrationReport> executeAllRegisteredTables(String dataSourceName) {
        return forDataSource(dataSourceName).executeAllRegisteredTables();
    }

    /**
     * 直接按数据源名称执行全部已注册物理表迁移。
     *
     * @param dataSourceName 数据源名称
     * @param builderCustomizer 可选 builder 自定义器
     * @return 迁移报告列表
     */
    default List<MigrationReport> executeAllRegisteredTables(String dataSourceName,
                                                             Consumer<EntityMigrationDefinition.Builder> builderCustomizer) {
        return forDataSource(dataSourceName).executeAllRegisteredTables(builderCustomizer);
    }
}
