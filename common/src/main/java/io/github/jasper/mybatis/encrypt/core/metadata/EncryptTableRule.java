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
    private final Map<String, EncryptJsonFieldRule> jsonFieldRules = new LinkedHashMap<String, EncryptJsonFieldRule>();

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
     * 注册 JSON 字段规则。
     *
     * @param rule JSON 字段规则
     */
    public void addJsonFieldRule(EncryptJsonFieldRule rule) {
        jsonFieldRules.put(rule.property(), rule);
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
     * 按属性名查找 JSON 字段规则。
     *
     * @param property 属性名
     * @return 命中的 JSON 字段规则
     */
    public Optional<EncryptJsonFieldRule> findJsonFieldByProperty(String property) {
        return Optional.ofNullable(jsonFieldRules.get(property));
    }

    /**
     * 按主表 JSON 列名查找 JSON 字段规则。
     *
     * @param column JSON 列名
     * @return 命中的 JSON 字段规则
     */
    public Optional<EncryptJsonFieldRule> findJsonFieldByColumn(String column) {
        String normalized = NameUtils.normalizeIdentifier(column);
        for (EncryptJsonFieldRule rule : jsonFieldRules.values()) {
            if (NameUtils.normalizeIdentifier(rule.column()).equals(normalized)) {
                return Optional.of(rule);
            }
        }
        return Optional.empty();
    }

    /**
     * 按主表 JSON 列名与精确 path 查找 path 规则。
     *
     * @param column JSON 列名
     * @param path 精确 JSON path
     * @return 命中的 path 规则
     */
    public Optional<EncryptJsonPathRule> findJsonPathRule(String column, String path) {
        EncryptJsonFieldRule fieldRule = findJsonFieldByColumn(column).orElse(null);
        if (fieldRule == null) {
            return Optional.empty();
        }
        for (EncryptJsonPathRule pathRule : fieldRule.pathRules()) {
            if (pathRule.path().equals(path)) {
                return Optional.of(pathRule);
            }
        }
        return Optional.empty();
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
     * 返回当前表下全部 JSON 字段规则。
     *
     * @return JSON 字段规则集合
     */
    public Collection<EncryptJsonFieldRule> getJsonFieldRules() {
        return jsonFieldRules.values();
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
        incoming.getJsonFieldRules().forEach(rule -> jsonFieldRules.putIfAbsent(rule.property(), rule));
        return this;
    }

    /**
     * 合并单个字段规则，已有规则优先保留。
     *
     * @param incoming 新字段规则
     * @return 当前对象
     */
    public EncryptTableRule mergeMissing(EncryptColumnRule incoming) {
        columnRules.putIfAbsent(NameUtils.normalizeIdentifier(incoming.column()), incoming);
        propertyRules.putIfAbsent(incoming.property(), incoming);
        return this;
    }

    /**
     * 合并单个 JSON 字段规则，已有规则优先保留。
     *
     * @param incoming 新 JSON 字段规则
     * @return 当前对象
     */
    public EncryptTableRule mergeMissing(EncryptJsonFieldRule incoming) {
        jsonFieldRules.putIfAbsent(incoming.property(), incoming);
        return this;
    }
}
