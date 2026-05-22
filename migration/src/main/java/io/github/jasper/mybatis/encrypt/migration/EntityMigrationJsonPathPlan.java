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

    public String getPath() {
        return path;
    }

    public String getStorageTable() {
        return storageTable;
    }

    public String getStorageIdColumn() {
        return storageIdColumn;
    }

    public String getHashColumn() {
        return hashColumn;
    }

    public String getCipherColumn() {
        return cipherColumn;
    }

    public String getCipherAlgorithm() {
        return cipherAlgorithm;
    }

    public String getAssistedQueryAlgorithm() {
        return assistedQueryAlgorithm;
    }
}
