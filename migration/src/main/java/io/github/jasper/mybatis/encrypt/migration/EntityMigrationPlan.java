package io.github.jasper.mybatis.encrypt.migration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable entity-scoped migration plan derived from registered metadata.
 */
public final class EntityMigrationPlan {

    private final String dataSourceName;
    private final Class<?> entityType;
    private final String entityName;
    private final String tableName;
    private final List<String> cursorColumns;
    private final int batchSize;
    private final boolean verifyAfterWrite;
    private final List<EntityMigrationColumnPlan> columnPlans;

    /**
     * Create one immutable entity migration plan.
     *
     * @param entityType registered entity type, or {@code null} for table-driven tasks
     * @param entityName entity or task display name
     * @param tableName normalized main-table name
     * @param cursorColumns ordered stable cursor columns in the main table
     * @param batchSize migration batch size
     * @param verifyAfterWrite whether write-after verification is enabled
     * @param columnPlans immutable column migration plans
     */
    public EntityMigrationPlan(String dataSourceName,
                               Class<?> entityType,
                               String entityName,
                               String tableName,
                               List<String> cursorColumns,
                               int batchSize,
                               boolean verifyAfterWrite,
                               List<EntityMigrationColumnPlan> columnPlans) {
        this.dataSourceName = dataSourceName;
        this.entityType = entityType;
        this.entityName = entityName;
        this.tableName = tableName;
        this.cursorColumns = Collections.unmodifiableList(new ArrayList<>(cursorColumns));
        this.batchSize = batchSize;
        this.verifyAfterWrite = verifyAfterWrite;
        this.columnPlans = Collections.unmodifiableList(columnPlans);
    }

    /**
     * Create one immutable entity migration plan.
     *
     * @param entityType registered entity type, or {@code null} for table-driven tasks
     * @param entityName entity or task display name
     * @param tableName normalized main-table name
     * @param cursorColumns ordered stable cursor columns in the main table
     * @param batchSize migration batch size
     * @param verifyAfterWrite whether write-after verification is enabled
     * @param columnPlans immutable column migration plans
     */
    public EntityMigrationPlan(Class<?> entityType,
                               String entityName,
                               String tableName,
                               List<String> cursorColumns,
                               int batchSize,
                               boolean verifyAfterWrite,
                               List<EntityMigrationColumnPlan> columnPlans) {
        this(null, entityType, entityName, tableName, cursorColumns, batchSize, verifyAfterWrite, columnPlans);
    }

    /**
     * Return the registered entity type.
     *
     * @return entity type
     */
    public Class<?> getEntityType() {
        return entityType;
    }

    /**
     * Return the bound data source name when the task is created from a global routing factory.
     *
     * @return data source name, or {@code null}
     */
    public String getDataSourceName() {
        return dataSourceName;
    }

    /**
     * Return the entity or task display name used in reports and state files.
     *
     * @return entity or task display name
     */
    public String getEntityName() {
        return entityName;
    }

    /**
     * Return the normalized main-table name.
     *
     * @return main-table name
     */
    public String getTableName() {
        return tableName;
    }

    /**
     * Return ordered stable cursor columns in the main table.
     *
     * @return immutable ordered cursor columns
     */
    public List<String> getCursorColumns() {
        return cursorColumns;
    }

    /**
     * Return the first stable cursor column in the main table.
     *
     * @return first cursor column name
     */
    public String getCursorColumn() {
        return cursorColumns.get(0);
    }

    /**
     * Return the first stable cursor column in the main table.
     *
     * @return first cursor column name
     * @deprecated use {@link #getCursorColumns()}
     */
    @Deprecated
    public String getIdColumn() {
        return getCursorColumn();
    }

    /**
     * Return the migration batch size.
     *
     * @return batch size
     */
    public int getBatchSize() {
        return batchSize;
    }

    /**
     * Return whether write-after verification is enabled.
     *
     * @return {@code true} when verification is enabled
     */
    public boolean isVerifyAfterWrite() {
        return verifyAfterWrite;
    }

    /**
     * Return the immutable column migration plans.
     *
     * @return column plans
     */
    public List<EntityMigrationColumnPlan> getColumnPlans() {
        return columnPlans;
    }
}
