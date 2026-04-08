package io.github.jasper.mybatis.encrypt.core.rewrite;

import java.util.List;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptTableRule;
import io.github.jasper.mybatis.encrypt.core.metadata.FieldStorageMode;

/**
 * 派生表规则构建器。
 *
 * <p>它专门负责把子查询投影结果重新折叠为一份新的 `EncryptTableRule`，
 * 让外层查询仍然可以按逻辑字段名继续做加密列解析。这部分逻辑本质上是“元数据投影”，
 * 与普通 SQL AST 改写职责不同，因此独立成 builder。</p>
 */
final class DerivedTableRuleBuilder {

    private static final String HIDDEN_ASSISTED_PREFIX = "__enc_assisted_";
    private static final String HIDDEN_LIKE_PREFIX = "__enc_like_";

    private final EncryptMetadataRegistry metadataRegistry;

    DerivedTableRuleBuilder(EncryptMetadataRegistry metadataRegistry) {
        this.metadataRegistry = metadataRegistry;
    }

    EncryptTableRule build(String alias, Select select) {
        if (select instanceof ParenthesedSelect parenthesedSelect && parenthesedSelect.getSelect() != null) {
            return build(alias, parenthesedSelect.getSelect());
        }
        if (select instanceof SetOperationList setOperationList) {
            return setOperationList.getSelects().isEmpty() ? null : build(alias, setOperationList.getSelect(0));
        }
        if (!(select instanceof PlainSelect plainSelect)) {
            return null;
        }
        SqlTableContext childContext = new SqlTableContext();
        registerLookupFromItem(childContext, plainSelect.getFromItem());
        if (plainSelect.getJoins() != null) {
            for (Join join : plainSelect.getJoins()) {
                registerLookupFromItem(childContext, join.getRightItem());
            }
        }
        EncryptTableRule derivedRule = new EncryptTableRule(alias);
        List<SelectItem<?>> selectItems = plainSelect.getSelectItems();
        if (selectItems == null) {
            return null;
        }
        for (SelectItem<?> item : selectItems) {
            Expression expression = item.getExpression();
            if (expression instanceof AllTableColumns allTableColumns) {
                for (EncryptColumnRule rule : childContext.rulesForSelectExpansion(allTableColumns.getTable())) {
                    if (!rule.isStoredInSeparateTable()) {
                        derivedRule.addColumnRule(projectDerivedRule(rule.column(), rule));
                    }
                }
                continue;
            }
            if (expression instanceof AllColumns) {
                for (EncryptColumnRule rule : childContext.rulesForSelectExpansion(null)) {
                    if (!rule.isStoredInSeparateTable()) {
                        derivedRule.addColumnRule(projectDerivedRule(rule.column(), rule));
                    }
                }
                continue;
            }
            if (!(expression instanceof Column column)) {
                continue;
            }
            EncryptColumnRule sourceRule = childContext.resolveProjected(column).orElse(null);
            if (sourceRule == null || sourceRule.isStoredInSeparateTable()) {
                continue;
            }
            String aliasName = item.getAlias() != null && item.getAlias().getName() != null && !item.getAlias().getName().isBlank()
                    ? item.getAlias().getName()
                    : column.getColumnName();
            if (aliasName.startsWith(HIDDEN_ASSISTED_PREFIX) || aliasName.startsWith(HIDDEN_LIKE_PREFIX)) {
                continue;
            }
            derivedRule.addColumnRule(projectDerivedRule(aliasName, sourceRule));
        }
        return derivedRule.getColumnRules().isEmpty() ? null : derivedRule;
    }

    void registerLookupFromItem(SqlTableContext tableContext, FromItem fromItem) {
        if (fromItem instanceof Table table) {
            registerTable(tableContext, table);
            return;
        }
        if (fromItem instanceof ParenthesedSelect parenthesedSelect && parenthesedSelect.getAlias() != null
                && parenthesedSelect.getAlias().getName() != null && parenthesedSelect.getSelect() != null) {
            EncryptTableRule derivedRule = build(parenthesedSelect.getAlias().getName(), parenthesedSelect.getSelect());
            if (derivedRule != null) {
                tableContext.registerDerived(parenthesedSelect.getAlias().getName(), derivedRule);
            }
        }
    }

    private EncryptColumnRule projectDerivedRule(String projectedColumn, EncryptColumnRule sourceRule) {
        return new EncryptColumnRule(
                projectedColumn,
                projectedColumn,
                sourceRule.cipherAlgorithm(),
                sourceRule.hasAssistedQueryColumn() ? HIDDEN_ASSISTED_PREFIX + projectedColumn : null,
                sourceRule.assistedQueryAlgorithm(),
                sourceRule.hasLikeQueryColumn() ? HIDDEN_LIKE_PREFIX + projectedColumn : null,
                sourceRule.likeQueryAlgorithm(),
                FieldStorageMode.SAME_TABLE,
                null,
                projectedColumn,
                sourceRule.sourceIdProperty(),
                sourceRule.sourceIdColumn(),
                sourceRule.storageIdColumn()
        );
    }

    private void registerTable(SqlTableContext tableContext, Table table) {
        EncryptTableRule rule = metadataRegistry.findByTable(table.getName()).orElse(null);
        if (rule == null) {
            return;
        }
        tableContext.register(table.getName(), table.getAlias() != null ? table.getAlias().getName() : null, rule);
    }
}
