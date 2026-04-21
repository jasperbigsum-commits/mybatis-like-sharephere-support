package io.github.jasper.mybatis.encrypt.core.metadata;

import io.github.jasper.mybatis.encrypt.util.StringUtils;
import io.github.jasper.mybatis.encrypt.util.NameUtils;
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
    private final String maskedColumn;
    private final String maskedAlgorithm;
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
     * @param maskedColumn 脱敏字段查询列
     * @param maskedAlgorithm 脱敏字段 辅助算法 bean 名称
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
                             String maskedColumn,
                             String maskedAlgorithm,
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
        this.maskedColumn = maskedColumn;
        this.maskedAlgorithm = maskedAlgorithm;
        this.storageMode = storageMode;
        this.storageTable = storageTable;
        this.storageColumn = storageColumn;
        this.storageIdColumn = storageIdColumn;
    }

    /**
     * 兼容旧构造签名，未显式配置存储态脱敏列时使用空配置。
     *
     * @param property 实体属性名
     * @param table 字段来源的物理表
     * @param column 应用 SQL 使用的原始业务列名
     * @param cipherAlgorithm 加密算法 bean 名称
     * @param assistedQueryColumn 辅助等值查询列
     * @param assistedQueryAlgorithm 辅助等值算法 bean 名称
     * @param likeQueryColumn LIKE 辅助查询列
     * @param likeQueryAlgorithm LIKE 辅助算法 bean 名称
     * @param storageMode 密文存储模式
     * @param storageTable 启用独立表模式时使用的外部密文表
     * @param storageColumn 实际密文存储列
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
        this(property, table, column, cipherAlgorithm, assistedQueryColumn, assistedQueryAlgorithm,
                likeQueryColumn, likeQueryAlgorithm, null, null, storageMode, storageTable, storageColumn,
                storageIdColumn);
    }

    /**
     * 实体属性名
     * @return 实体属性名
     */
    public String property() {
        return property;
    }

    /**
     * 字段来源的物理表；为空时默认继承所属实体规则的表名
     * @return 字段来源的物理表
     */
    public String table() {
        return table;
    }

    /**
     * 获取 应用 SQL 使用的原始业务列名
     * @return 应用 SQL 使用的原始业务列名
     */
    public String column() {
        return column;
    }

    /**
     * 获取加密算法 bean 名称
     * @return 加密算法 bean 名称
     */
    public String cipherAlgorithm() {
        return cipherAlgorithm;
    }

    /**
     * 辅助等值查询列
     * @return 辅助等值查询列
     */
    public String assistedQueryColumn() {
        return assistedQueryColumn;
    }

    /**
     * 获取辅助等值算法 bean 名称
     * @return 辅助等值算法 bean 名称
     */
    public String assistedQueryAlgorithm() {
        return assistedQueryAlgorithm;
    }

    /**
     * 获取  LIKE 辅助查询列
     * @return  LIKE 辅助查询列
     */
    public String likeQueryColumn() {
        return likeQueryColumn;
    }

    /**
     * 获取 LIKE 辅助算法 bean 名称
     * @return LIKE 辅助算法 bean 名称
     */
    public String likeQueryAlgorithm() {
        return likeQueryAlgorithm;
    }

    /**
     * 获取存储态脱敏列。
     *
     * @return 存储态脱敏列
     */
    public String maskedColumn() {
        return maskedColumn;
    }

    /**
     * 获取存储态脱敏算法 bean 名称。
     *
     * @return 存储态脱敏算法 bean 名称
     */
    public String maskedAlgorithm() {
        return maskedAlgorithm;
    }

    /**
     * 获取存储态脱敏列实际使用的算法。
     *
     * <p>当 LIKE 查询列和存储态脱敏列复用同一物理列时，该列只能有一个生成值，
     * 因此统一使用 LIKE 查询算法生成，避免写入路径产生重复列和不一致值。</p>
     *
     * @return 存储态脱敏列实际使用的算法 bean 名称
     */
    public String effectiveMaskedAlgorithm() {
        return sharesLikeQueryAndMaskedColumn() ? likeQueryAlgorithm : maskedAlgorithm;
    }

    /**
     * 判断 LIKE 查询列和存储态脱敏列是否复用同一物理列。
     *
     * @return 复用同一列时返回 {@code true}
     */
    public boolean sharesLikeQueryAndMaskedColumn() {
        return sameColumn(likeQueryColumn, maskedColumn);
    }

    /**
     * 判断是否存在独立于 LIKE 查询列的存储态脱敏列。
     *
     * @return 存储态脱敏列独立存在时返回 {@code true}
     */
    public boolean hasDistinctMaskedColumn() {
        return hasMaskedColumn() && !sharesLikeQueryAndMaskedColumn();
    }

    /**
     * 获取密文存储模式
     * @return 密文存储模式
     */
    public FieldStorageMode storageMode() {
        return storageMode;
    }

    /**
     * 获取启用独立表模式时使用的外部密文表
     * @return 启用独立表模式时使用的外部密文表
     */
    public String storageTable() {
        return storageTable;
    }

    /**
     * 获取实际密文存储列，默认与 {@code column} 一致
     * @return 获取实际密文存储列
     */
    public String storageColumn() {
        return storageColumn;
    }

    /**
     * 获取外部表标识列
     * @return 外部表标识列
     */
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
     * 判断是否配置了存储态脱敏列。
     *
     * @return 配置了存储态脱敏列时返回 {@code true}
     */
    public boolean hasMaskedColumn() {
        return StringUtils.isNotBlank(maskedColumn);
    }

    /**
     * 判断是否使用独立表存储。
     *
     * @return 独立表模式时返回 {@code true}
     */
    public boolean isStoredInSeparateTable() {
        return storageMode == FieldStorageMode.SEPARATE_TABLE;
    }

    private boolean sameColumn(String left, String right) {
        String normalizedLeft = NameUtils.normalizeIdentifier(left);
        String normalizedRight = NameUtils.normalizeIdentifier(right);
        return normalizedLeft != null && normalizedLeft.equals(normalizedRight);
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
                && Objects.equals(maskedColumn, that.maskedColumn)
                && Objects.equals(maskedAlgorithm, that.maskedAlgorithm)
                && storageMode == that.storageMode
                && Objects.equals(storageTable, that.storageTable)
                && Objects.equals(storageColumn, that.storageColumn)
                && Objects.equals(storageIdColumn, that.storageIdColumn);
    }

    @Override
    public int hashCode() {
        return Objects.hash(property, table, column, cipherAlgorithm, assistedQueryColumn,
                assistedQueryAlgorithm, likeQueryColumn, likeQueryAlgorithm, maskedColumn, maskedAlgorithm, storageMode,
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
                + ", maskedColumn='" + maskedColumn + '\''
                + ", maskedAlgorithm='" + maskedAlgorithm + '\''
                + ", storageMode=" + storageMode
                + ", storageTable='" + storageTable + '\''
                + ", storageColumn='" + storageColumn + '\''
                + ", storageIdColumn='" + storageIdColumn + '\''
                + '}';
    }
}
