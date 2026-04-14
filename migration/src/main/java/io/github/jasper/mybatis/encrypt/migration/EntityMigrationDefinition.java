package io.github.jasper.mybatis.encrypt.migration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * User-facing task definition based on a registered entity class.
 */
public final class EntityMigrationDefinition {

    private final Class<?> entityType;
    private final String tableName;
    private final List<String> cursorColumns;
    private final int batchSize;
    private final boolean verifyAfterWrite;
    private final Set<String> includedProperties;
    private final Map<String, String> backupColumns;

    private EntityMigrationDefinition(Builder builder) {
        if (builder.entityType == null && (builder.tableName == null || builder.tableName.trim().isEmpty())) {
            throw new IllegalArgumentException("entityType or tableName must not be null");
        }
        if (builder.cursorColumns.isEmpty()) {
            throw new IllegalArgumentException("cursorColumns must not be empty");
        }
        for (String cursorColumn : builder.cursorColumns) {
            if (cursorColumn == null || cursorColumn.trim().isEmpty()) {
                throw new IllegalArgumentException("cursorColumns must not contain blank items");
            }
        }
        if (builder.batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be greater than zero");
        }
        this.entityType = builder.entityType;
        this.tableName = builder.tableName;
        this.cursorColumns = Collections.unmodifiableList(new ArrayList<>(builder.cursorColumns));
        this.batchSize = builder.batchSize;
        this.verifyAfterWrite = builder.verifyAfterWrite;
        this.includedProperties = Collections.unmodifiableSet(new LinkedHashSet<>(builder.includedProperties));
        this.backupColumns = Collections.unmodifiableMap(new LinkedHashMap<>(builder.backupColumns));
    }

    /**
     * Create a builder for one entity migration task.
     *
     * @param entityType registered entity type
     * @param cursorColumn first stable cursor column in the main table
     * @param additionalCursorColumns additional stable cursor columns ordered by page key priority
     * @return new builder instance
     */
    public static Builder builder(Class<?> entityType, String cursorColumn, String... additionalCursorColumns) {
        return new Builder(entityType, null, mergeCursorColumns(cursorColumn, additionalCursorColumns));
    }

    /**
     * Create a builder for one table-name-driven migration task.
     *
     * @param tableName physical table name
     * @param cursorColumn first stable cursor column in the main table
     * @param additionalCursorColumns additional stable cursor columns ordered by page key priority
     * @return new builder instance
     */
    public static Builder builder(String tableName, String cursorColumn, String... additionalCursorColumns) {
        return new Builder(null, tableName, mergeCursorColumns(cursorColumn, additionalCursorColumns));
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
     * Return the physical table name when the task is table-driven.
     *
     * @return table name, or {@code null}
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
     * Return the batch size used during migration.
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
     * Return the explicitly included property names.
     *
     * @return immutable included property set
     */
    public Set<String> getIncludedProperties() {
        return includedProperties;
    }

    /**
     * Return configured plaintext backup columns keyed by property name.
     *
     * @return immutable property-to-backup-column mapping
     */
    public Map<String, String> getBackupColumns() {
        return backupColumns;
    }

    private static List<String> mergeCursorColumns(String cursorColumn, String... additionalCursorColumns) {
        List<String> cursorColumns = new ArrayList<>();
        cursorColumns.add(cursorColumn);
        if (additionalCursorColumns != null) {
            Collections.addAll(cursorColumns, additionalCursorColumns);
        }
        return cursorColumns;
    }

    /**
     * Builder for {@link EntityMigrationDefinition}.
     */
    public static final class Builder {

        private final Class<?> entityType;
        private final String tableName;
        private final List<String> cursorColumns;
        private int batchSize = 200;
        private boolean verifyAfterWrite = true;
        private final Set<String> includedProperties = new LinkedHashSet<>();
        private final Map<String, String> backupColumns = new LinkedHashMap<>();

        private Builder(Class<?> entityType, String tableName, List<String> cursorColumns) {
            this.entityType = entityType;
            this.tableName = tableName;
            this.cursorColumns = new ArrayList<>(cursorColumns);
        }

        /**
         * Override the default batch size.
         *
         * @param batchSize batch size, must be greater than zero
         * @return current builder
         */
        public Builder batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        /**
         * Enable or disable write-after verification.
         *
         * @param verifyAfterWrite whether to verify migrated rows after write
         * @return current builder
         */
        public Builder verifyAfterWrite(boolean verifyAfterWrite) {
            this.verifyAfterWrite = verifyAfterWrite;
            return this;
        }

        /**
         * Restrict the task to one encrypted property.
         *
         * @param property entity property name to include
         * @return current builder
         */
        public Builder includeProperty(String property) {
            this.includedProperties.add(property);
            return this;
        }

        /**
         * Persist the original plaintext value to one backup column when migration overwrites the source column.
         *
         * @param property encrypted property name
         * @param backupColumn backup column in the main table
         * @return current builder
         */
        public Builder backupColumn(String property, String backupColumn) {
            if (property == null || property.trim().isEmpty()) {
                throw new IllegalArgumentException("property must not be blank");
            }
            if (backupColumn == null || backupColumn.trim().isEmpty()) {
                throw new IllegalArgumentException("backupColumn must not be blank");
            }
            this.backupColumns.put(property, backupColumn);
            return this;
        }

        /**
         * Build the immutable migration definition.
         *
         * @return immutable migration definition
         */
        public EntityMigrationDefinition build() {
            return new EntityMigrationDefinition(this);
        }
    }
}
