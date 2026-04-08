package io.github.jasper.mybatis.encrypt.core.metadata;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import io.github.jasper.mybatis.encrypt.util.NameUtils;

/**
 * 表级加密规则。
 *
 * <p>同一张表下维护两套索引：按数据库列名查找，供 SQL 改写使用；按实体属性名查找，
 * 供结果解密使用。</p>
 */
public class EncryptTableRule {

    private final String tableName;
    private final Map<String, EncryptColumnRule> columnRules = new LinkedHashMap<>();
    private final Map<String, EncryptColumnRule> propertyRules = new LinkedHashMap<>();

    /**
     * 创建表级加密规则。
     *
     * @param tableName 物理表名
     */
    public EncryptTableRule(String tableName) {
        this.tableName = NameUtils.normalizeIdentifier(tableName);
    }

    /**
     * 返回标准化后的物理表名。
     *
     * @return 物理表名
     */
    public String getTableName() {
        return tableName;
    }

    /**
     * 注册字段规则。
     *
     * @param rule 字段级加密规则
     */
    public void addColumnRule(EncryptColumnRule rule) {
        columnRules.put(NameUtils.normalizeIdentifier(rule.column()), rule);
        propertyRules.put(rule.property(), rule);
    }

    /**
     * 按数据库列名查找字段规则。
     *
     * @param column 数据库列名
     * @return 命中的字段规则
     */
    public Optional<EncryptColumnRule> findByColumn(String column) {
        return Optional.ofNullable(columnRules.get(NameUtils.normalizeIdentifier(column)));
    }

    /**
     * 按实体属性名查找字段规则。
     *
     * @param property 实体属性名
     * @return 命中的字段规则
     */
    public Optional<EncryptColumnRule> findByProperty(String property) {
        return Optional.ofNullable(propertyRules.get(property));
    }

    /**
     * 返回当前表下全部字段规则。
     *
     * @return 字段规则集合
     */
    public Collection<EncryptColumnRule> getColumnRules() {
        return columnRules.values();
    }

    /**
     * 合并缺失字段规则，已有规则优先保留。
     *
     * @param incoming 新加载到的规则
     * @return 当前对象
     */
    public EncryptTableRule mergeMissing(EncryptTableRule incoming) {
        incoming.getColumnRules().forEach(rule -> columnRules.putIfAbsent(NameUtils.normalizeIdentifier(rule.column()), rule));
        incoming.getColumnRules().forEach(rule -> propertyRules.putIfAbsent(rule.property(), rule));
        return this;
    }
}
