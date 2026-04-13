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

    /**
     * Create one immutable column migration plan.
     *
     * @param property entity property name
     * @param sourceColumn plaintext source column in the main table
     * @param storageColumn cipher storage column
     * @param assistedQueryColumn deterministic hash column for equality lookup and separate-table linkage
     * @param likeQueryColumn optional LIKE lookup column
     * @param cipherAlgorithm cipher algorithm bean name
     * @param assistedQueryAlgorithm assisted query algorithm bean name
     * @param likeQueryAlgorithm like query algorithm bean name
     * @param storedInSeparateTable whether the field uses separate-table storage
     * @param storageTable separate-table name when enabled
     * @param storageIdColumn physical primary key column in the separate table
     */
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

    /**
     * Return the entity property name.
     *
     * @return entity property name
     */
    public String getProperty() {
        return property;
    }

    /**
     * Return the plaintext source column in the main table.
     *
     * @return source column name
     */
    public String getSourceColumn() {
        return sourceColumn;
    }

    /**
     * Return the physical cipher storage column.
     *
     * @return cipher storage column
     */
    public String getStorageColumn() {
        return storageColumn;
    }

    /**
     * Return the deterministic hash column used for equality lookup and separate-table linkage.
     *
     * @return assisted query column
     */
    public String getAssistedQueryColumn() {
        return assistedQueryColumn;
    }

    /**
     * Return the optional LIKE lookup column.
     *
     * @return like lookup column, or {@code null}
     */
    public String getLikeQueryColumn() {
        return likeQueryColumn;
    }

    /**
     * Return the cipher algorithm bean name.
     *
     * @return cipher algorithm bean name
     */
    public String getCipherAlgorithm() {
        return cipherAlgorithm;
    }

    /**
     * Return the assisted query algorithm bean name.
     *
     * @return assisted query algorithm bean name
     */
    public String getAssistedQueryAlgorithm() {
        return assistedQueryAlgorithm;
    }

    /**
     * Return the LIKE query algorithm bean name.
     *
     * @return like query algorithm bean name
     */
    public String getLikeQueryAlgorithm() {
        return likeQueryAlgorithm;
    }

    /**
     * Return whether the field uses separate-table storage.
     *
     * @return {@code true} when stored in a separate table
     */
    public boolean isStoredInSeparateTable() {
        return storedInSeparateTable;
    }

    /**
     * Return the separate-table name when enabled.
     *
     * @return separate-table name, or {@code null}
     */
    public String getStorageTable() {
        return storageTable;
    }

    /**
     * Return the physical primary key column in the separate table.
     *
     * @return separate-table id column
     */
    public String getStorageIdColumn() {
        return storageIdColumn;
    }
}
