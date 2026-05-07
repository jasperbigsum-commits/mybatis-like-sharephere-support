package io.github.jasper.mybatis.encrypt.core.rewrite;

import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptTableRule;
import io.github.jasper.mybatis.encrypt.util.StringUtils;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;

/**
 * 构建 `SELECT` 查询块的表规则上下文。
 *
 * <p>负责注册主表、JOIN 右表以及派生表别名，并在需要时递归触发子查询改写，
 * 让 {@link SqlRewriteEngine} 只保留 statement 级流程编排。</p>
 */
final class SqlSelectTableContextBuilder {

    private final EncryptMetadataRegistry metadataRegistry;
    private final DerivedTableRuleBuilder derivedTableRuleBuilder;
    private final SqlConditionRewriter.SelectRewriteDispatcher selectRewriteDispatcher;

    SqlSelectTableContextBuilder(EncryptMetadataRegistry metadataRegistry,
                                 DerivedTableRuleBuilder derivedTableRuleBuilder,
                                 SqlConditionRewriter.SelectRewriteDispatcher selectRewriteDispatcher) {
        this.metadataRegistry = metadataRegistry;
        this.derivedTableRuleBuilder = derivedTableRuleBuilder;
        this.selectRewriteDispatcher = selectRewriteDispatcher;
    }

    SqlTableContext build(PlainSelect plainSelect, SqlRewriteContext context) {
        return build(plainSelect, context, null);
    }

    SqlTableContext build(PlainSelect plainSelect, SqlRewriteContext context, SqlTableContext outerTableContext) {
        SqlTableContext tableContext = new SqlTableContext(outerTableContext);
        registerFromItem(tableContext, plainSelect.getFromItem(), context);
        if (plainSelect.getJoins() != null) {
            for (Join join : plainSelect.getJoins()) {
                registerFromItem(tableContext, join.getRightItem(), context);
            }
        }
        return tableContext;
    }

    void registerTable(SqlTableContext tableContext, Table table) {
        EncryptTableRule rule = metadataRegistry.findByTable(table.getName()).orElse(null);
        if (rule == null) {
            return;
        }
        tableContext.register(table.getName(), table.getAlias() != null ? table.getAlias().getName() : null, rule);
    }

    private void registerFromItem(SqlTableContext tableContext, FromItem fromItem, SqlRewriteContext context) {
        if (fromItem instanceof Table) {
            registerTable(tableContext, (Table) fromItem);
            return;
        }
        if (!(fromItem instanceof ParenthesedSelect)) {
            return;
        }
        ParenthesedSelect parenthesedSelect = (ParenthesedSelect) fromItem;
        if (parenthesedSelect.getSelect() == null) {
            return;
        }
        selectRewriteDispatcher.rewrite(parenthesedSelect.getSelect(), context, ProjectionMode.DERIVED, null);
        if (parenthesedSelect.getAlias() == null
                || !StringUtils.isNotBlank(parenthesedSelect.getAlias().getName())) {
            return;
        }
        EncryptTableRule derivedRule = derivedTableRuleBuilder.build(parenthesedSelect.getAlias().getName(),
                parenthesedSelect.getSelect());
        if (derivedRule != null) {
            tableContext.registerDerived(parenthesedSelect.getAlias().getName(), derivedRule);
        }
    }
}
