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

    public String path() {
        return path;
    }

    public String storageTable() {
        return storageTable;
    }

    public String storageIdColumn() {
        return storageIdColumn;
    }

    public String hashColumn() {
        return hashColumn;
    }

    public String cipherColumn() {
        return cipherColumn;
    }

    public String cipherAlgorithm() {
        return cipherAlgorithm;
    }

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
