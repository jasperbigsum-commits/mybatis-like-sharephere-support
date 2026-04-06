package tech.jasper.mybatis.encrypt.core.metadata;

/**
 * Field-level encryption rule.
 *
 * @param property entity property name
 * @param column original business column name used by application SQL
 * @param cipherAlgorithm cipher algorithm bean name
 * @param assistedQueryColumn assisted equality query column
 * @param assistedQueryAlgorithm assisted equality algorithm bean name
 * @param likeQueryColumn LIKE helper column
 * @param likeQueryAlgorithm LIKE helper algorithm bean name
 * @param storageMode ciphertext storage mode
 * @param storageTable external ciphertext table when separate-table mode is enabled
 * @param storageColumn real ciphertext storage column, defaulting to {@code column}
 * @param sourceIdProperty entity identifier property used for separate-table linkage, inferred from sourceIdColumn when omitted
 * @param sourceIdColumn business-table identifier column
 * @param storageIdColumn external-table identifier column
 */
public record EncryptColumnRule(String property,
                                String column,
                                String cipherAlgorithm,
                                String assistedQueryColumn,
                                String assistedQueryAlgorithm,
                                String likeQueryColumn,
                                String likeQueryAlgorithm,
                                FieldStorageMode storageMode,
                                String storageTable,
                                String storageColumn,
                                String sourceIdProperty,
                                String sourceIdColumn,
                                String storageIdColumn) {

    public boolean hasAssistedQueryColumn() {
        return assistedQueryColumn != null && !assistedQueryColumn.isBlank();
    }

    public boolean hasLikeQueryColumn() {
        return likeQueryColumn != null && !likeQueryColumn.isBlank();
    }

    public boolean isStoredInSeparateTable() {
        return storageMode == FieldStorageMode.SEPARATE_TABLE;
    }
}
