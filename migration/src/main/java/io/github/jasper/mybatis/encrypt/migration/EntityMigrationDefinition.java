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

    public static Builder builder(Class<?> entityType, String idColumn) {
        return new Builder(entityType, idColumn);
    }

    public Class<?> getEntityType() {
        return entityType;
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

    public Set<String> getIncludedProperties() {
        return includedProperties;
    }

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

        public Builder batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        public Builder verifyAfterWrite(boolean verifyAfterWrite) {
            this.verifyAfterWrite = verifyAfterWrite;
            return this;
        }

        public Builder includeProperty(String property) {
            this.includedProperties.add(property);
            return this;
        }

        public EntityMigrationDefinition build() {
            return new EntityMigrationDefinition(this);
        }
    }
}
