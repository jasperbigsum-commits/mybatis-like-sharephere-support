package io.github.jasper.mybatis.encrypt.migration;

/**
 * Column-level mapping between plaintext source and encrypted storage fields.
 */
public final class EntityMigrationColumnPlan {

    private final String property;
    private final String sourceColumn;
    private final String storageColumn;
    private final String assistedQueryColumn;
    private final String likeQueryColumn;
    private final String cipherAlgorithm;
    private final String assistedQueryAlgorithm;
    private final String likeQueryAlgorithm;
    private final boolean storedInSeparateTable;
    private final String storageTable;
    private final String storageIdColumn;

    public EntityMigrationColumnPlan(String property,
                                     String sourceColumn,
                                     String storageColumn,
                                     String assistedQueryColumn,
                                     String likeQueryColumn,
                                     String cipherAlgorithm,
                                     String assistedQueryAlgorithm,
                                     String likeQueryAlgorithm,
                                     boolean storedInSeparateTable,
                                     String storageTable,
                                     String storageIdColumn) {
        this.property = property;
        this.sourceColumn = sourceColumn;
        this.storageColumn = storageColumn;
        this.assistedQueryColumn = assistedQueryColumn;
        this.likeQueryColumn = likeQueryColumn;
        this.cipherAlgorithm = cipherAlgorithm;
        this.assistedQueryAlgorithm = assistedQueryAlgorithm;
        this.likeQueryAlgorithm = likeQueryAlgorithm;
        this.storedInSeparateTable = storedInSeparateTable;
        this.storageTable = storageTable;
        this.storageIdColumn = storageIdColumn;
    }

    public String getProperty() {
        return property;
    }

    public String getSourceColumn() {
        return sourceColumn;
    }

    public String getStorageColumn() {
        return storageColumn;
    }

    public String getAssistedQueryColumn() {
        return assistedQueryColumn;
    }

    public String getLikeQueryColumn() {
        return likeQueryColumn;
    }

    public String getCipherAlgorithm() {
        return cipherAlgorithm;
    }

    public String getAssistedQueryAlgorithm() {
        return assistedQueryAlgorithm;
    }

    public String getLikeQueryAlgorithm() {
        return likeQueryAlgorithm;
    }

    public boolean isStoredInSeparateTable() {
        return storedInSeparateTable;
    }

    public String getStorageTable() {
        return storageTable;
    }

    public String getStorageIdColumn() {
        return storageIdColumn;
    }
}
