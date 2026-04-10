package io.github.jasper.mybatis.encrypt.core.rewrite;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptTableRule;
import io.github.jasper.mybatis.encrypt.exception.UnsupportedEncryptedOperationException;
import io.github.jasper.mybatis.encrypt.util.NameUtils;
import io.github.jasper.mybatis.encrypt.util.StringUtils;

/**
 * 当前查询块可见表规则的解析上下文。
 *
 * <p>它把真实表名、别名和派生表别名统一收敛到一份查找表里，负责回答“当前列到底属于哪张表”。
 * 复杂 JOIN、子查询、派生表场景下如果出现字段歧义或规则丢失，通常都和这里的注册结果有关。</p>
 */
final class SqlTableContext {

    private final Map<String, EncryptTableRule> ruleByAlias = new LinkedHashMap<>();

    void register(String tableName, String alias, EncryptTableRule rule) {
        ruleByAlias.put(NameUtils.normalizeIdentifier(tableName), rule);
        if (StringUtils.isNotBlank(alias)) {
            ruleByAlias.put(NameUtils.normalizeIdentifier(alias), rule);
        }
    }

    void registerDerived(String alias, EncryptTableRule rule) {
        if (StringUtils.isNotBlank(alias)) {
            ruleByAlias.put(NameUtils.normalizeIdentifier(alias), rule);
        }
    }

    Optional<EncryptColumnRule> resolve(Column column) {
        if (column.getTable() != null && StringUtils.isNotBlank(column.getTable().getName())) {
            EncryptTableRule tableRule = ruleByAlias.get(NameUtils.normalizeIdentifier(column.getTable().getName()));
            if (tableRule != null) {
                return tableRule.findByColumn(column.getColumnName());
            }
        }
        EncryptColumnRule candidate = null;
        for (EncryptTableRule tableRule : uniqueRules()) {
            EncryptColumnRule rule = tableRule.findByColumn(column.getColumnName()).orElse(null);
            if (rule == null) {
                continue;
            }
            if (candidate != null) {
                throw new UnsupportedEncryptedOperationException(
                        "Ambiguous encrypted column reference: " + column.getFullyQualifiedName());
            }
            candidate = rule;
        }
        return Optional.ofNullable(candidate);
    }

    List<EncryptColumnRule> rulesForSelectExpansion(Table table) {
        if (table != null && StringUtils.isNotBlank(table.getName())) {
            EncryptTableRule rule = ruleByAlias.get(NameUtils.normalizeIdentifier(table.getName()));
            return rule == null ? Collections.<EncryptColumnRule>emptyList() : new ArrayList<EncryptColumnRule>(rule.getColumnRules());
        }
        Collection<EncryptTableRule> uniqueRules = uniqueRules();
        if (uniqueRules.size() != 1) {
            return Collections.emptyList();
        }
        return new ArrayList<>(uniqueRules.iterator().next().getColumnRules());
    }

    Optional<EncryptColumnRule> resolveProjected(Column column) {
        if (column.getTable() != null && StringUtils.isNotBlank(column.getTable().getName())) {
            EncryptTableRule tableRule = ruleByAlias.get(NameUtils.normalizeIdentifier(column.getTable().getName()));
            if (tableRule != null) {
                return Optional.ofNullable(matchProjectedRule(tableRule, column.getColumnName()));
            }
        }
        EncryptColumnRule candidate = null;
        for (EncryptTableRule tableRule : uniqueRules()) {
            EncryptColumnRule rule = matchProjectedRule(tableRule, column.getColumnName());
            if (rule == null) {
                continue;
            }
            if (candidate != null) {
                throw new UnsupportedEncryptedOperationException(
                        "Ambiguous encrypted projection reference: " + column.getFullyQualifiedName());
            }
            candidate = rule;
        }
        return Optional.ofNullable(candidate);
    }

    boolean isEmpty() {
        return ruleByAlias.isEmpty();
    }

    private EncryptColumnRule matchProjectedRule(EncryptTableRule tableRule, String columnName) {
        String normalized = NameUtils.normalizeIdentifier(columnName);
        for (EncryptColumnRule rule : tableRule.getColumnRules()) {
            if (NameUtils.normalizeIdentifier(rule.column()).equals(normalized)
                    || NameUtils.normalizeIdentifier(rule.storageColumn()).equals(normalized)
                    || (rule.hasAssistedQueryColumn()
                    && NameUtils.normalizeIdentifier(rule.assistedQueryColumn()).equals(normalized))
                    || (rule.hasLikeQueryColumn()
                    && NameUtils.normalizeIdentifier(rule.likeQueryColumn()).equals(normalized))) {
                return rule;
            }
        }
        return null;
    }

    private Collection<EncryptTableRule> uniqueRules() {
        return new LinkedHashSet<>(ruleByAlias.values());
    }
}
