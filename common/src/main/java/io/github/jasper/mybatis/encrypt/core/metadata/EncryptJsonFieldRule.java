package io.github.jasper.mybatis.encrypt.core.metadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 单个 JSON 字符串字段的加密规则。
 */
public final class EncryptJsonFieldRule {

    private final String property;
    private final String table;
    private final String column;
    private final String cipherAlgorithm;
    private final String assistedQueryAlgorithm;
    private final List<EncryptJsonPathRule> pathRules;

    /**
     * 创建 JSON 字段规则。
     *
     * @param property 属性名
     * @param table 来源表名
     * @param column JSON 列名
     * @param cipherAlgorithm 字段默认密文算法
     * @param assistedQueryAlgorithm 字段默认 hash 算法
     * @param pathRules path 规则集合
     */
    public EncryptJsonFieldRule(String property,
                                String table,
                                String column,
                                String cipherAlgorithm,
                                String assistedQueryAlgorithm,
                                List<EncryptJsonPathRule> pathRules) {
        this.property = property;
        this.table = table;
        this.column = column;
        this.cipherAlgorithm = cipherAlgorithm;
        this.assistedQueryAlgorithm = assistedQueryAlgorithm;
        this.pathRules = pathRules == null
                ? Collections.<EncryptJsonPathRule>emptyList()
                : Collections.unmodifiableList(new ArrayList<EncryptJsonPathRule>(pathRules));
    }

    /**
     * 返回实体属性名。
     *
     * @return 实体属性名
     */
    public String property() {
        return property;
    }

    /**
     * 返回字段来源表名。
     *
     * @return 字段来源表名
     */
    public String table() {
        return table;
    }

    /**
     * 返回主表 JSON 物理列名。
     *
     * @return JSON 列名
     */
    public String column() {
        return column;
    }

    /**
     * 返回字段默认密文算法 bean 名称。
     *
     * @return 密文算法 bean 名称
     */
    public String cipherAlgorithm() {
        return cipherAlgorithm;
    }

    /**
     * 返回字段默认辅助查询算法 bean 名称。
     *
     * @return 辅助查询算法 bean 名称
     */
    public String assistedQueryAlgorithm() {
        return assistedQueryAlgorithm;
    }

    /**
     * 返回该 JSON 字段下声明的精确 path 规则。
     *
     * @return 不可变 path 规则列表
     */
    public List<EncryptJsonPathRule> pathRules() {
        return pathRules;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof EncryptJsonFieldRule)) {
            return false;
        }
        EncryptJsonFieldRule that = (EncryptJsonFieldRule) other;
        return Objects.equals(property, that.property)
                && Objects.equals(table, that.table)
                && Objects.equals(column, that.column)
                && Objects.equals(cipherAlgorithm, that.cipherAlgorithm)
                && Objects.equals(assistedQueryAlgorithm, that.assistedQueryAlgorithm)
                && Objects.equals(pathRules, that.pathRules);
    }

    @Override
    public int hashCode() {
        return Objects.hash(property, table, column, cipherAlgorithm, assistedQueryAlgorithm, pathRules);
    }
}
