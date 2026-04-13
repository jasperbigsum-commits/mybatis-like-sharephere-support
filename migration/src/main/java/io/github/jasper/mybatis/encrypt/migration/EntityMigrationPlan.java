package io.github.jasper.mybatis.encrypt.migration;

import java.util.Collections;
import java.util.List;

/**
 * Immutable entity-scoped migration plan derived from registered metadata.
 */
public final class EntityMigrationPlan {

    private final Class<?> entityType;
    private final String tableName;
    private final String idColumn;
    private final int batchSize;
    private final boolean verifyAfterWrite;
    private final List<EntityMigrationColumnPlan> columnPlans;

    /**
     * Create one immutable entity migration plan.
     *
     * @param entityType registered entity type
     * @param tableName normalized main-table name
     * @param idColumn primary key column in the main table
     * @param batchSize migration batch size
     * @param verifyAfterWrite whether write-after verification is enabled
     * @param columnPlans immutable column migration plans
     */
    public EntityMigrationPlan(Class<?> entityType,
                               String tableName,
                               String idColumn,
                               int batchSize,
                               boolean verifyAfterWrite,
                               List<EntityMigrationColumnPlan> columnPlans) {
        this.entityType = entityType;
        this.tableName = tableName;
        this.idColumn = idColumn;
        this.batchSize = batchSize;
        this.verifyAfterWrite = verifyAfterWrite;
        this.columnPlans = Collections.unmodifiableList(columnPlans);
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
     * Return the normalized main-table name.
     *
     * @return main-table name
     */
    public String getTableName() {
        return tableName;
    }

    /**
     * Return the primary key column in the main table.
     *
     * @return id column name
     */
    public String getIdColumn() {
        return idColumn;
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
