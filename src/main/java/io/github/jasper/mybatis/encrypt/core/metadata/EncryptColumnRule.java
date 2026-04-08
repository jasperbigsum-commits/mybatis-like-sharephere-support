package io.github.jasper.mybatis.encrypt.core.metadata;

/**
 * 字段级加密规则。
 *
 * @param property 实体属性名
 * @param column 应用 SQL 使用的原始业务列名
 * @param cipherAlgorithm 加密算法 bean 名称
 * @param assistedQueryColumn 辅助等值查询列
 * @param assistedQueryAlgorithm 辅助等值算法 bean 名称
 * @param likeQueryColumn LIKE 辅助查询列
 * @param likeQueryAlgorithm LIKE 辅助算法 bean 名称
 * @param storageMode 密文存储模式
 * @param storageTable 启用独立表模式时使用的外部密文表
 * @param storageColumn 实际密文存储列，默认与 {@code column} 一致
 * @param sourceIdProperty 独立表关联使用的实体标识属性，省略时根据 sourceIdColumn 推断
 * @param sourceIdColumn 业务表标识列
 * @param storageIdColumn 外部表标识列
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

    /**
     * 判断是否配置了辅助等值查询列。
     *
     * @return 配置了辅助列时返回 {@code true}
     */
    public boolean hasAssistedQueryColumn() {
        return assistedQueryColumn != null && !assistedQueryColumn.isBlank();
    }

    /**
     * 判断是否配置了 LIKE 查询列。
     *
     * @return 配置了 LIKE 列时返回 {@code true}
     */
    public boolean hasLikeQueryColumn() {
        return likeQueryColumn != null && !likeQueryColumn.isBlank();
    }

    /**
     * 判断是否使用独立表存储。
     *
     * @return 独立表模式时返回 {@code true}
     */
    public boolean isStoredInSeparateTable() {
        return storageMode == FieldStorageMode.SEPARATE_TABLE;
    }
}
