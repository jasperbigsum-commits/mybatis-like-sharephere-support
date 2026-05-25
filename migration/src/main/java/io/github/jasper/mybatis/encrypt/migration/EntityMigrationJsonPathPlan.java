package io.github.jasper.mybatis.encrypt.migration;

/**
 * Immutable migration plan for one exact JSON path.
 */
public final class EntityMigrationJsonPathPlan {

    private final String path;
    private final String storageTable;
    private final String storageIdColumn;
    private final String hashColumn;
    private final String cipherColumn;
    private final String cipherAlgorithm;
    private final String assistedQueryAlgorithm;

    /**
     * Create one immutable JSON path migration plan.
     *
     * @param path exact JSON path
     * @param storageTable external table name
     * @param storageIdColumn external table primary key column
     * @param hashColumn external hash column
     * @param cipherColumn external cipher column
     * @param cipherAlgorithm cipher algorithm bean name
     * @param assistedQueryAlgorithm hash algorithm bean name
     */
    public EntityMigrationJsonPathPlan(String path,
                                       String storageTable,
                                       String storageIdColumn,
                                       String hashColumn,
                                       String cipherColumn,
                                       String cipherAlgorithm,
                                       String assistedQueryAlgorithm) {
        this.path = path;
        this.storageTable = storageTable;
        this.storageIdColumn = storageIdColumn;
        this.hashColumn = hashColumn;
        this.cipherColumn = cipherColumn;
        this.cipherAlgorithm = cipherAlgorithm;
        this.assistedQueryAlgorithm = assistedQueryAlgorithm;
    }

    /**
     * Return the exact JSON path.
     *
     * @return exact JSON path
     */
    public String getPath() {
        return path;
    }

    /**
     * Return the external table used to store ciphertext for this path.
     *
     * @return external storage table
     */
    public String getStorageTable() {
        return storageTable;
    }

    /**
     * Return the external table primary key column.
     *
     * @return external table primary key column
     */
    public String getStorageIdColumn() {
        return storageIdColumn;
    }

    /**
     * Return the hash column used to bind the main-table JSON value to the external row.
     *
     * @return hash column
     */
    public String getHashColumn() {
        return hashColumn;
    }

    /**
     * Return the external table ciphertext column.
     *
     * @return ciphertext column
     */
    public String getCipherColumn() {
        return cipherColumn;
    }

    /**
     * Return the cipher algorithm bean name for this path.
     *
     * @return cipher algorithm bean name
     */
    public String getCipherAlgorithm() {
        return cipherAlgorithm;
    }

    /**
     * Return the assisted-query algorithm bean name for this path.
     *
     * @return assisted-query algorithm bean name
     */
    public String getAssistedQueryAlgorithm() {
        return assistedQueryAlgorithm;
    }
}
