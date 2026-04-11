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

    public Class<?> getEntityType() {
        return entityType;
    }

    public String getTableName() {
        return tableName;
    }

    public String getIdColumn() {
        return idColumn;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public boolean isVerifyAfterWrite() {
        return verifyAfterWrite;
    }

    public List<EntityMigrationColumnPlan> getColumnPlans() {
        return columnPlans;
    }
}
