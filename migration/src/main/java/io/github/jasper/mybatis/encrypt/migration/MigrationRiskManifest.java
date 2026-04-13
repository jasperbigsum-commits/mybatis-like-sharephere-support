package io.github.jasper.mybatis.encrypt.migration;

import java.util.Collections;
import java.util.List;

/**
 * Entity-scoped mutation manifest used for operator confirmation.
 */
public final class MigrationRiskManifest {

    private final String entityName;
    private final String tableName;
    private final List<MigrationRiskEntry> entries;

    /**
     * Create one immutable risk manifest.
     *
     * @param entityName entity simple name
     * @param tableName main-table name
     * @param entries affected mutation entries
     */
    public MigrationRiskManifest(String entityName, String tableName, List<MigrationRiskEntry> entries) {
        this.entityName = entityName;
        this.tableName = tableName;
        this.entries = Collections.unmodifiableList(entries);
    }

    /**
     * Return the entity simple name.
     *
     * @return entity name
     */
    public String getEntityName() {
        return entityName;
    }

    /**
     * Return the main-table name.
     *
     * @return table name
     */
    public String getTableName() {
        return tableName;
    }

    /**
     * Return the immutable risk entries.
     *
     * @return risk entries
     */
    public List<MigrationRiskEntry> getEntries() {
        return entries;
    }
}
