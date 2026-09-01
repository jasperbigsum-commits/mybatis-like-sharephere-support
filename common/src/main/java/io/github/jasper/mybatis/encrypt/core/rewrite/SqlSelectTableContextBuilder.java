package io.github.jasper.mybatis.encrypt.core.rewrite;

import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptTableRule;
import io.github.jasper.mybatis.encrypt.util.StringUtils;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.WithItem;

/**
 * 构建 `SELECT` 查询块的表规则上下文。
 *
 * <p>负责注册主表、JOIN 右表以及派生表别名，并在需要时递归触发子查询改写，
 * 让 {@link SqlRewriteEngine} 只保留 statement 级流程编排。</p>
 *
 * <p>CTE (`WITH`) 在当前查询块中表现为命名派生表，因此也在这里注册。注册前会先按
 * {@link ProjectionMode#DERIVED} 改写 CTE body，让外层继续使用 CTE 别名引用逻辑加密列时，
 * 能解析到隐藏的 assisted/like helper 投影。</p>
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
        registerWithItems(tableContext, plainSelect, context);
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

    /**
     * 将当前查询块的 CTE 注册为派生表规则。
     *
     * <p>只处理带别名且有 SELECT body 的 CTE。递归 CTE 中如果引用了加密字段，会在
     * {@link SqlRewriteEngine} 的前置校验中被拒绝；不引用加密字段的递归 CTE 即使进入这里，
     * 也不会生成加密派生表规则。</p>
     *
     * @param tableContext 当前查询块表上下文
     * @param select 当前 SELECT 查询块
     * @param context 当前 SQL 改写上下文
     */
    private void registerWithItems(SqlTableContext tableContext, Select select, SqlRewriteContext context) {
        if (select.getWithItemsList() == null) {
            return;
        }
        for (WithItem withItem : select.getWithItemsList()) {
            if (withItem == null || withItem.getSelect() == null || withItem.getAlias() == null
                    || !StringUtils.isNotBlank(withItem.getAlias().getName())) {
                continue;
            }
            // A non-recursive CTE is visible to the current query block like a named derived table.
            selectRewriteDispatcher.rewrite(withItem.getSelect(), context, ProjectionMode.DERIVED, null);
            EncryptTableRule derivedRule = derivedTableRuleBuilder.build(withItem.getAlias().getName(),
                    withItem.getSelect());
            if (derivedRule != null) {
                tableContext.registerDerived(withItem.getAlias().getName(), derivedRule);
            }
        }
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
