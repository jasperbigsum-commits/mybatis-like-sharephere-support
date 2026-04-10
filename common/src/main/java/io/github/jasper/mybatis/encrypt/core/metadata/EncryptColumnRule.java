package io.github.jasper.mybatis.encrypt.core.metadata;

import io.github.jasper.mybatis.encrypt.util.StringUtils;
import java.util.Objects;

/**
 * 字段级加密规则。
 */
public final class EncryptColumnRule {

    private final String property;
    private final String table;
    private final String column;
    private final String cipherAlgorithm;
    private final String assistedQueryColumn;
    private final String assistedQueryAlgorithm;
    private final String likeQueryColumn;
    private final String likeQueryAlgorithm;
    private final FieldStorageMode storageMode;
    private final String storageTable;
    private final String storageColumn;
    private final String storageIdColumn;

    /**
     * 字段级加密规则
     * @param property 实体属性名
     * @param table 字段来源的物理表；为空时默认继承所属实体规则的表名
     * @param column 应用 SQL 使用的原始业务列名
     * @param cipherAlgorithm 加密算法 bean 名称
     * @param assistedQueryColumn 辅助等值查询列
     * @param assistedQueryAlgorithm 辅助等值算法 bean 名称
     * @param likeQueryColumn LIKE 辅助查询列
     * @param likeQueryAlgorithm LIKE 辅助算法 bean 名称
     * @param storageMode 密文存储模式
     * @param storageTable 启用独立表模式时使用的外部密文表
     * @param storageColumn 实际密文存储列，默认与 {@code column} 一致
     * @param storageIdColumn 外部表标识列
     */
    public EncryptColumnRule(String property,
                             String table,
                             String column,
                             String cipherAlgorithm,
                             String assistedQueryColumn,
                             String assistedQueryAlgorithm,
                             String likeQueryColumn,
                             String likeQueryAlgorithm,
                             FieldStorageMode storageMode,
                             String storageTable,
                             String storageColumn,
                             String storageIdColumn) {
        this.property = property;
        this.table = table;
        this.column = column;
        this.cipherAlgorithm = cipherAlgorithm;
        this.assistedQueryColumn = assistedQueryColumn;
        this.assistedQueryAlgorithm = assistedQueryAlgorithm;
        this.likeQueryColumn = likeQueryColumn;
        this.likeQueryAlgorithm = likeQueryAlgorithm;
        this.storageMode = storageMode;
        this.storageTable = storageTable;
        this.storageColumn = storageColumn;
        this.storageIdColumn = storageIdColumn;
    }

    public String property() {
        return property;
    }

    public String table() {
        return table;
    }

    public String column() {
        return column;
    }

    public String cipherAlgorithm() {
        return cipherAlgorithm;
    }

    public String assistedQueryColumn() {
        return assistedQueryColumn;
    }

    public String assistedQueryAlgorithm() {
        return assistedQueryAlgorithm;
    }

    public String likeQueryColumn() {
        return likeQueryColumn;
    }

    public String likeQueryAlgorithm() {
        return likeQueryAlgorithm;
    }

    public FieldStorageMode storageMode() {
        return storageMode;
    }

    public String storageTable() {
        return storageTable;
    }

    public String storageColumn() {
        return storageColumn;
    }

    public String storageIdColumn() {
        return storageIdColumn;
    }

    /**
     * 判断是否配置了辅助等值查询列。
     *
     * @return 配置了辅助列时返回 {@code true}
     */
    public boolean hasAssistedQueryColumn() {
        return StringUtils.isNotBlank(assistedQueryColumn);
    }

    /**
     * 判断是否配置了 LIKE 查询列。
     *
     * @return 配置了 LIKE 列时返回 {@code true}
     */
    public boolean hasLikeQueryColumn() {
        return StringUtils.isNotBlank(likeQueryColumn);
    }

    /**
     * 判断是否使用独立表存储。
     *
     * @return 独立表模式时返回 {@code true}
     */
    public boolean isStoredInSeparateTable() {
        return storageMode == FieldStorageMode.SEPARATE_TABLE;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof EncryptColumnRule)) {
            return false;
        }
        EncryptColumnRule that = (EncryptColumnRule) other;
        return Objects.equals(property, that.property)
                && Objects.equals(table, that.table)
                && Objects.equals(column, that.column)
                && Objects.equals(cipherAlgorithm, that.cipherAlgorithm)
                && Objects.equals(assistedQueryColumn, that.assistedQueryColumn)
                && Objects.equals(assistedQueryAlgorithm, that.assistedQueryAlgorithm)
                && Objects.equals(likeQueryColumn, that.likeQueryColumn)
                && Objects.equals(likeQueryAlgorithm, that.likeQueryAlgorithm)
                && storageMode == that.storageMode
                && Objects.equals(storageTable, that.storageTable)
                && Objects.equals(storageColumn, that.storageColumn)
                && Objects.equals(storageIdColumn, that.storageIdColumn);
    }

    @Override
    public int hashCode() {
        return Objects.hash(property, table, column, cipherAlgorithm, assistedQueryColumn,
                assistedQueryAlgorithm, likeQueryColumn, likeQueryAlgorithm, storageMode,
                storageTable, storageColumn, storageIdColumn);
    }

    @Override
    public String toString() {
        return "EncryptColumnRule{"
                + "property='" + property + '\''
                + ", table='" + table + '\''
                + ", column='" + column + '\''
                + ", cipherAlgorithm='" + cipherAlgorithm + '\''
                + ", assistedQueryColumn='" + assistedQueryColumn + '\''
                + ", assistedQueryAlgorithm='" + assistedQueryAlgorithm + '\''
                + ", likeQueryColumn='" + likeQueryColumn + '\''
                + ", likeQueryAlgorithm='" + likeQueryAlgorithm + '\''
                + ", storageMode=" + storageMode
                + ", storageTable='" + storageTable + '\''
                + ", storageColumn='" + storageColumn + '\''
                + ", storageIdColumn='" + storageIdColumn + '\''
                + '}';
    }
}
