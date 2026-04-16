package io.github.jasper.mybatis.encrypt.migration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Entity-scoped mutation manifest used for operator confirmation.
 */
public final class MigrationRiskManifest {

    private final String dataSourceName;
    private final String entityName;
    private final String tableName;
    private final List<String> cursorColumns;
    private final List<MigrationRiskEntry> entries;

    /**
     * Create one immutable risk manifest.
     *
     * @param entityName entity simple name
     * @param tableName main-table name
     * @param cursorColumns ordered cursor columns in the main table
     * @param entries affected mutation entries
     */
    public MigrationRiskManifest(String dataSourceName,
                                 String entityName,
                                 String tableName,
                                 List<String> cursorColumns,
                                 List<MigrationRiskEntry> entries) {
        this.dataSourceName = dataSourceName;
        this.entityName = entityName;
        this.tableName = tableName;
        this.cursorColumns = cursorColumns == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(cursorColumns));
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
     * Return the bound data source name when present.
     *
     * @return data source name, or {@code null}
     */
    public String getDataSourceName() {
        return dataSourceName;
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
     * Return ordered cursor columns in the main table.
     *
     * @return immutable cursor columns
     */
    public List<String> getCursorColumns() {
        return cursorColumns;
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
