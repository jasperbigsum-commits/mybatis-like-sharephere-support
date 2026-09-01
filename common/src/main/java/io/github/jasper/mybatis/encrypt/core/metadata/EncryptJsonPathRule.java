package io.github.jasper.mybatis.encrypt.core.metadata;

import java.util.Objects;

/**
 * 单个精确 JSON path 的加密绑定规则。
 */
public final class EncryptJsonPathRule {

    private final String path;
    private final String storageTable;
    private final String storageIdColumn;
    private final String hashColumn;
    private final String cipherColumn;
    private final String cipherAlgorithm;
    private final String assistedQueryAlgorithm;

    /**
     * 创建 JSON path 规则。
     *
     * @param path 精确 JSON path
     * @param storageTable 独立表名
     * @param storageIdColumn 独立表主键列
     * @param hashColumn hash 列
     * @param cipherColumn 密文列
     * @param cipherAlgorithm 密文算法 bean 名称
     * @param assistedQueryAlgorithm hash 算法 bean 名称
     */
    public EncryptJsonPathRule(String path,
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
     * 返回精确 JSON path。
     *
     * @return JSON path
     */
    public String path() {
        return path;
    }

    /**
     * 返回保存该 path 密文的独立表名。
     *
     * @return 独立表名
     */
    public String storageTable() {
        return storageTable;
    }

    /**
     * 返回独立表主键列名。
     *
     * @return 独立表主键列名
     */
    public String storageIdColumn() {
        return storageIdColumn;
    }

    /**
     * 返回主表 JSON 中保存 hash 引用值的列名。
     *
     * @return hash 列名
     */
    public String hashColumn() {
        return hashColumn;
    }

    /**
     * 返回独立表中保存密文的列名。
     *
     * @return 密文列名
     */
    public String cipherColumn() {
        return cipherColumn;
    }

    /**
     * 返回该 path 使用的密文算法 bean 名称。
     *
     * @return 密文算法 bean 名称
     */
    public String cipherAlgorithm() {
        return cipherAlgorithm;
    }

    /**
     * 返回该 path 使用的辅助查询算法 bean 名称。
     *
     * @return 辅助查询算法 bean 名称
     */
    public String assistedQueryAlgorithm() {
        return assistedQueryAlgorithm;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof EncryptJsonPathRule)) {
            return false;
        }
        EncryptJsonPathRule that = (EncryptJsonPathRule) other;
        return Objects.equals(path, that.path)
                && Objects.equals(storageTable, that.storageTable)
                && Objects.equals(storageIdColumn, that.storageIdColumn)
                && Objects.equals(hashColumn, that.hashColumn)
                && Objects.equals(cipherColumn, that.cipherColumn)
                && Objects.equals(cipherAlgorithm, that.cipherAlgorithm)
                && Objects.equals(assistedQueryAlgorithm, that.assistedQueryAlgorithm);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, storageTable, storageIdColumn, hashColumn, cipherColumn, cipherAlgorithm,
                assistedQueryAlgorithm);
    }
}
