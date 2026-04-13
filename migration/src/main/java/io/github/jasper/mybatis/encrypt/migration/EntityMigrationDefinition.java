package io.github.jasper.mybatis.encrypt.migration;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * User-facing task definition based on a registered entity class.
 */
public final class EntityMigrationDefinition {

    private final Class<?> entityType;
    private final String idColumn;
    private final int batchSize;
    private final boolean verifyAfterWrite;
    private final Set<String> includedProperties;

    private EntityMigrationDefinition(Builder builder) {
        if (builder.entityType == null) {
            throw new IllegalArgumentException("entityType must not be null");
        }
        if (builder.idColumn == null || builder.idColumn.trim().isEmpty()) {
            throw new IllegalArgumentException("idColumn must not be blank");
        }
        if (builder.batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be greater than zero");
        }
        this.entityType = builder.entityType;
        this.idColumn = builder.idColumn;
        this.batchSize = builder.batchSize;
        this.verifyAfterWrite = builder.verifyAfterWrite;
        this.includedProperties = Collections.unmodifiableSet(new LinkedHashSet<String>(builder.includedProperties));
    }

    /**
     * Create a builder for one entity migration task.
     *
     * @param entityType registered entity type
     * @param idColumn primary key column in the main table
     * @return new builder instance
     */
    public static Builder builder(Class<?> entityType, String idColumn) {
        return new Builder(entityType, idColumn);
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
     * Return the primary key column in the main table.
     *
     * @return id column name
     */
    public String getIdColumn() {
        return idColumn;
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
     * Builder for {@link EntityMigrationDefinition}.
     */
    public static final class Builder {

        private final Class<?> entityType;
        private final String idColumn;
        private int batchSize = 200;
        private boolean verifyAfterWrite = true;
        private final Set<String> includedProperties = new LinkedHashSet<String>();

        private Builder(Class<?> entityType, String idColumn) {
            this.entityType = entityType;
            this.idColumn = idColumn;
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
         * Build the immutable migration definition.
         *
         * @return immutable migration definition
         */
        public EntityMigrationDefinition build() {
            return new EntityMigrationDefinition(this);
        }
    }
}
