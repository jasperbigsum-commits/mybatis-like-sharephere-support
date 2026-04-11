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

    public MigrationRiskManifest(String entityName, String tableName, List<MigrationRiskEntry> entries) {
        this.entityName = entityName;
        this.tableName = tableName;
        this.entries = Collections.unmodifiableList(entries);
    }

    public String getEntityName() {
        return entityName;
    }

    public String getTableName() {
        return tableName;
    }

    public List<MigrationRiskEntry> getEntries() {
        return entries;
    }
}
