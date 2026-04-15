package io.github.jasper.mybatis.encrypt.core.rewrite;

import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import io.github.jasper.mybatis.encrypt.exception.EncryptionErrorCode;
import io.github.jasper.mybatis.encrypt.exception.UnsupportedEncryptedOperationException;
import io.github.jasper.mybatis.encrypt.util.StringUtils;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.SelectItem;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * `SELECT` 投影列表改写器。
 *
 * <p>负责处理普通查询、比较型子查询和派生表三种投影模式，
 * 避免 {@link SqlRewriteEngine} 同时承载流程编排与列投影细节。</p>
 */
final class SqlSelectProjectionRewriter {

    private final BiFunction<Expression, SqlTableContext, ColumnResolution> encryptedColumnResolver;
    private final BiFunction<Column, String, Column> columnBuilder;
    private final BiFunction<EncryptColumnRule, String, String> assistedQueryColumnProvider;

    SqlSelectProjectionRewriter(BiFunction<Expression, SqlTableContext, ColumnResolution> encryptedColumnResolver,
                                BiFunction<Column, String, Column> columnBuilder,
                                BiFunction<EncryptColumnRule, String, String> assistedQueryColumnProvider) {
        this.encryptedColumnResolver = encryptedColumnResolver;
        this.columnBuilder = columnBuilder;
        this.assistedQueryColumnProvider = assistedQueryColumnProvider;
    }

    boolean rewrite(PlainSelect plainSelect, SqlTableContext tableContext, ProjectionMode projectionMode) {
        if (projectionMode == ProjectionMode.COMPARISON) {
            return rewriteComparisonSelectItems(plainSelect, tableContext);
        }
        return rewriteSelectItems(plainSelect, tableContext, projectionMode);
    }

    /**
     * 改写普通 `SELECT` 的投影列表。
     *
     * <p>`NORMAL` 模式输出业务可见的逻辑列别名；`DERIVED` 模式会额外注入隐藏的辅助列别名，
     * 供外层查询继续按逻辑字段名改写条件。通配符场景会优先输出逻辑别名列，再保留原始 `*`，
     * 避免 JDBC/MyBatis 在重复列名时优先读取到 wildcard 中的旧列值。</p>
     */
    private boolean rewriteSelectItems(PlainSelect plainSelect,
                                       SqlTableContext tableContext,
                                       ProjectionMode projectionMode) {
        if (plainSelect.getSelectItems() == null) {
            return false;
        }
        boolean changed = false;
        List<SelectItem<?>> rewritten = new ArrayList<>();
        for (SelectItem<?> item : plainSelect.getSelectItems()) {
            Expression expression = item.getExpression();
            if (expression instanceof AllTableColumns) {
                AllTableColumns allTableColumns = (AllTableColumns) expression;
                if (appendSelectAliasesForWildcard(rewritten,
                        tableContext.rulesForSelectExpansion(allTableColumns.getTable()),
                        allTableColumns.getTable(), projectionMode)) {
                    changed = true;
                }
                rewritten.add(item);
                continue;
            }
            if (expression instanceof AllColumns) {
                if (appendSelectAliasesForWildcard(rewritten, tableContext.rulesForSelectExpansion(null), null,
                        projectionMode)) {
                    changed = true;
                }
                rewritten.add(item);
                continue;
            }
            ColumnResolution resolution = encryptedColumnResolver.apply(expression, tableContext);
            if (resolution == null) {
                rewritten.add(item);
                continue;
            }
            if (resolution.rule().isStoredInSeparateTable()) {
                rewritten.add(item);
                changed = true;
                continue;
            }
            rewritten.add(buildSelectStorageItem(item, resolution));
            changed = true;
            if (projectionMode == ProjectionMode.DERIVED) {
                appendDerivedHelperSelectItems(rewritten, item, resolution);
            }
        }
        if (!rewritten.isEmpty()) {
            plainSelect.setSelectItems(rewritten);
        }
        return changed;
    }

    /**
     * 改写 `IN (subquery)` 右侧子查询的投影列表。
     *
     * <p>比较型子查询不能保留业务逻辑列语义，只能输出真正参与比较的物理列，
     * 否则外层 `IN` 左右两侧含义会不一致。</p>
     */
    private boolean rewriteComparisonSelectItems(PlainSelect plainSelect, SqlTableContext tableContext) {
        if (plainSelect.getSelectItems() == null) {
            return false;
        }
        boolean changed = false;
        List<SelectItem<?>> rewritten = new ArrayList<>();
        for (SelectItem<?> item : plainSelect.getSelectItems()) {
            Expression expression = item.getExpression();
            if (expression instanceof AllColumns) {
                throw new UnsupportedEncryptedOperationException(EncryptionErrorCode.UNSUPPORTED_ENCRYPTED_IN_QUERY,
                        "Wildcard select is not supported in encrypted IN subquery.");
            }
            ColumnResolution resolution = encryptedColumnResolver.apply(expression, tableContext);
            if (resolution == null) {
                rewritten.add(item);
                continue;
            }
            if (resolution.rule().isStoredInSeparateTable()) {
                throw new UnsupportedEncryptedOperationException(EncryptionErrorCode.UNSUPPORTED_ENCRYPTED_IN_QUERY,
                        "Separate-table encrypted field is not supported in IN subquery.");
            }
            rewritten.add(buildComparisonSelectItem(item, resolution));
            changed = true;
        }
        if (!rewritten.isEmpty()) {
            plainSelect.setSelectItems(rewritten);
        }
        return changed;
    }

    private boolean appendSelectAliasesForWildcard(List<SelectItem<?>> target,
                                                   List<EncryptColumnRule> rules,
                                                   Table tableReference,
                                                   ProjectionMode projectionMode) {
        boolean appended = false;
        for (EncryptColumnRule rule : rules) {
            if (rule.isStoredInSeparateTable()) {
                continue;
            }
            Column sourceColumn = new Column(rule.column());
            if (tableReference != null && StringUtils.isNotBlank(tableReference.getName())) {
                sourceColumn.setTable(new Table(tableReference.getName()));
            }
            ColumnResolution resolution = new ColumnResolution(sourceColumn, rule, true);
            SelectItem<?> storageItem = buildSelectStorageItem(new SelectItem<>(sourceColumn), resolution);
            target.add(storageItem);
            appended = true;
            if (projectionMode == ProjectionMode.DERIVED) {
                appendDerivedHelperSelectItems(target, storageItem, resolution);
            }
        }
        return appended;
    }

    private void appendDerivedHelperSelectItems(List<SelectItem<?>> target,
                                                SelectItem<?> originalItem,
                                                ColumnResolution resolution) {
        String logicalAlias = selectAliasName(originalItem, resolution);
        if (resolution.rule().hasAssistedQueryColumn()) {
            target.add(SelectItem.from(
                    columnBuilder.apply(resolution.column(), resolution.rule().assistedQueryColumn()),
                    new Alias(hiddenAssistedAlias(logicalAlias), false)
            ));
        }
        if (resolution.rule().hasLikeQueryColumn()) {
            target.add(SelectItem.from(
                    columnBuilder.apply(resolution.column(), resolution.rule().likeQueryColumn()),
                    new Alias(hiddenLikeAlias(logicalAlias), false)
            ));
        }
    }

    private SelectItem<?> buildSelectStorageItem(SelectItem<?> originalItem, ColumnResolution resolution) {
        Alias alias = originalItem.getAlias() != null
                ? originalItem.getAlias()
                : new Alias(resolution.column().getColumnName(), false);
        return SelectItem.from(columnBuilder.apply(resolution.column(), resolution.rule().storageColumn()), alias);
    }

    private SelectItem<?> buildComparisonSelectItem(SelectItem<?> originalItem, ColumnResolution resolution) {
        String targetColumn = assistedQueryColumnProvider.apply(resolution.rule(), "comparison subquery");
        Alias alias = originalItem.getAlias() != null
                ? originalItem.getAlias()
                : new Alias(resolution.column().getColumnName(), false);
        return SelectItem.from(columnBuilder.apply(resolution.column(), targetColumn), alias);
    }

    private String selectAliasName(SelectItem<?> item, ColumnResolution resolution) {
        return item.getAlias() != null && item.getAlias().getName() != null && StringUtils.isNotBlank(item.getAlias().getName())
                ? item.getAlias().getName()
                : resolution.column().getColumnName();
    }

    private String hiddenAssistedAlias(String logicalAlias) {
        return "__enc_assisted_" + logicalAlias;
    }

    private String hiddenLikeAlias(String logicalAlias) {
        return "__enc_like_" + logicalAlias;
    }
}
