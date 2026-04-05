package tech.jasper.mybatis.encrypt.core.metadata;

/**
 * 字段级加密规则。
 *
 * @param property 实体属性名
 * @param column 主加密列名
 * @param cipherAlgorithm 主加密算法 Bean 名称
 * @param assistedQueryColumn 辅助等值查询列名
 * @param assistedQueryAlgorithm 辅助等值查询算法 Bean 名称
 * @param likeQueryColumn LIKE 查询辅助列名
 * @param likeQueryAlgorithm LIKE 查询算法 Bean 名称
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
