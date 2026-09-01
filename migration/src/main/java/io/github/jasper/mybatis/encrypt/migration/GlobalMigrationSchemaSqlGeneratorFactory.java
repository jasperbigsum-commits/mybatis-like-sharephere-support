package io.github.jasper.mybatis.encrypt.migration;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 面向多数据源的全局 DDL 生成工厂。
 */
public interface GlobalMigrationSchemaSqlGeneratorFactory {

    /**
     * 返回已注册的数据源名称集合。
     *
     * @return 可用数据源名称
     */
    Set<String> getDataSourceNames();

    /**
     * 绑定到指定数据源的 DDL 生成器。
     *
     * @param dataSourceName 数据源名称
     * @return 绑定后的 DDL 生成器
     */
    MigrationSchemaSqlGenerator forDataSource(String dataSourceName);

    /**
     * 直接按数据源名称为指定实体生成 DDL。
     *
     * @param dataSourceName 数据源名称
     * @param entityType 实体类型
     * @return DDL 列表
     */
    default List<String> generateForEntity(String dataSourceName, Class<?> entityType) {
        return forDataSource(dataSourceName).generateForEntity(entityType);
    }

    /**
     * 直接按数据源名称为指定实体生成 DDL。
     *
     * @param dataSourceName 数据源名称
     * @param entityType 实体类型
     * @param builderCustomizer 自定义迁移定义
     * @return DDL 列表
     */
    default List<String> generateForEntity(String dataSourceName,
                                           Class<?> entityType,
                                           Consumer<EntityMigrationDefinition.Builder> builderCustomizer) {
        return forDataSource(dataSourceName).generateForEntity(entityType, builderCustomizer);
    }

    /**
     * 直接按数据源名称为指定表生成 DDL。
     *
     * @param dataSourceName 数据源名称
     * @param tableName 物理表名
     * @return DDL 列表
     */
    default List<String> generateForTable(String dataSourceName, String tableName) {
        return forDataSource(dataSourceName).generateForTable(tableName);
    }

    /**
     * 直接按数据源名称为指定表生成 DDL。
     *
     * @param dataSourceName 数据源名称
     * @param tableName 物理表名
     * @param builderCustomizer 自定义迁移定义
     * @return DDL 列表
     */
    default List<String> generateForTable(String dataSourceName,
                                          String tableName,
                                          Consumer<EntityMigrationDefinition.Builder> builderCustomizer) {
        return forDataSource(dataSourceName).generateForTable(tableName, builderCustomizer);
    }

    /**
     * 直接按数据源名称批量生成全部已注册表的 DDL。
     *
     * @param dataSourceName 数据源名称
     * @return 扁平 DDL 列表
     */
    default List<String> generateAllRegisteredTables(String dataSourceName) {
        return forDataSource(dataSourceName).generateAllRegisteredTables();
    }

    /**
     * 直接按数据源名称批量生成全部已注册表的 DDL。
     *
     * @param dataSourceName 数据源名称
     * @param builderCustomizer 自定义迁移定义
     * @return 扁平 DDL 列表
     */
    default List<String> generateAllRegisteredTables(String dataSourceName,
                                                     Consumer<EntityMigrationDefinition.Builder> builderCustomizer) {
        return forDataSource(dataSourceName).generateAllRegisteredTables(builderCustomizer);
    }

    /**
     * 直接按数据源名称分组生成全部已注册表的 DDL。
     *
     * @param dataSourceName 数据源名称
     * @return 按表分组的 DDL
     */
    default Map<String, List<String>> generateAllRegisteredTablesGrouped(String dataSourceName) {
        return forDataSource(dataSourceName).generateAllRegisteredTablesGrouped();
    }

    /**
     * 直接按数据源名称分组生成全部已注册表的 DDL。
     *
     * @param dataSourceName 数据源名称
     * @param builderCustomizer 自定义迁移定义
     * @return 按表分组的 DDL
     */
    default Map<String, List<String>> generateAllRegisteredTablesGrouped(
            String dataSourceName,
            Consumer<EntityMigrationDefinition.Builder> builderCustomizer) {
        return forDataSource(dataSourceName).generateAllRegisteredTablesGrouped(builderCustomizer);
    }
}
