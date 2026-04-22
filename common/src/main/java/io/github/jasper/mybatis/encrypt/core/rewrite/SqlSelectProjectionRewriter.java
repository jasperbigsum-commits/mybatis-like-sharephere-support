package io.github.jasper.mybatis.encrypt.core.rewrite;

import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import io.github.jasper.mybatis.encrypt.exception.EncryptionErrorCode;
import io.github.jasper.mybatis.encrypt.exception.UnsupportedEncryptedOperationException;
import io.github.jasper.mybatis.encrypt.util.NameUtils;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
     * 供外层查询继续按逻辑字段名改写条件。通配符场景会优先输出逻辑别名列，再保留 `*` 或 `table.*`，
     * 避免 JDBC/MyBatis 在重复列名时优先读取到 wildcard 中的旧列值，同时规避 `expr, *` 的方言兼容问题。</p>
     */
    private boolean rewriteSelectItems(PlainSelect plainSelect,
                                       SqlTableContext tableContext,
                                       ProjectionMode projectionMode) {
        if (plainSelect.getSelectItems() == null) {
            return false;
        }
        boolean changed = false;
        List<SelectItem<?>> rewritten = new ArrayList<>();
        Table implicitWildcardTable = resolveImplicitWildcardTable(plainSelect);
        Table wildcardExpansionTable = resolveWildcardExpansionTable(plainSelect);
        boolean hasBareWildcard = containsBareWildcard(plainSelect);
        boolean unsupportedBareWildcard = hasBareWildcard && implicitWildcardTable == null;
        // projectedKeys 记录当前 SELECT 已经暴露给外层/结果集的逻辑列名；
        // wildcardInjectedKeys 只记录由 * / t.* 自动补出来的逻辑列，便于后续显式列去重。
        Set<String> projectedKeys = new LinkedHashSet<>();
        Set<String> wildcardInjectedKeys = new LinkedHashSet<>();
        boolean singleTableContext = implicitWildcardTable != null;
        for (SelectItem<?> item : plainSelect.getSelectItems()) {
            Expression expression = item.getExpression();
            if (expression instanceof AllTableColumns) {
                AllTableColumns allTableColumns = (AllTableColumns) expression;
                if (appendSelectAliasesForWildcard(rewritten,
                        tableContext.rulesForSelectExpansion(allTableColumns.getTable()),
                        allTableColumns.getTable(), projectionMode, projectedKeys,
                        wildcardInjectedKeys, singleTableContext)) {
                    changed = true;
                }
                rewritten.add(item);
                continue;
            }
            if (expression instanceof AllColumns) {
                if (unsupportedBareWildcard) {
                    throwUnsupportedBareWildcard();
                }
                boolean appended = appendSelectAliasesForWildcard(rewritten, tableContext.rulesForSelectExpansion(null),
                        wildcardExpansionTable, projectionMode, projectedKeys, wildcardInjectedKeys, true);
                if (appended) {
                    changed = true;
                }
                boolean mixedWildcardProjection = plainSelect.getSelectItems().size() > 1;
                if (implicitWildcardTable != null && (appended || mixedWildcardProjection)) {
                    // 只在通配符与其他投影并存时把裸 * 收窄成 table.*。
                    // 单独 SELECT * 且 storageColumn == column 时保持原样，避免引入额外方言差异。
                    rewritten.add(new SelectItem<>(new AllTableColumns(implicitWildcardTable)));
                    changed = true;
                } else {
                    rewritten.add(item);
                }
                continue;
            }
            ColumnResolution resolution = encryptedColumnResolver.apply(expression, tableContext);
            if (resolution == null) {
                rewritten.add(item);
                registerProjectedKey(projectedKeys, item);
                continue;
            }
            if (resolution.rule().isStoredInSeparateTable()) {
                rewritten.add(item);
                registerProjectedKey(projectedKeys, item);
                changed = true;
                continue;
            }
            // 如果同一个逻辑列已经被前面的 wildcard 注入过，这里跳过显式列，
            // 避免生成 `select phone_cipher as phone, *, phone_cipher as phone` 这类重复投影。
            if (containsProjectedKey(wildcardInjectedKeys, resolution.column().getTable(),
                    selectAliasName(item, resolution))) {
                changed = true;
                continue;
            }
            rewritten.add(buildSelectStorageItem(item, resolution));
            registerProjectedKey(projectedKeys, resolution.column().getTable(), selectAliasName(item, resolution));
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
                                                   ProjectionMode projectionMode,
                                                   Set<String> projectedKeys,
                                                   Set<String> wildcardInjectedKeys,
                                                   boolean registerUnqualifiedKey) {
        boolean appended = false;
        for (EncryptColumnRule rule : rules) {
            if (rule.isStoredInSeparateTable()) {
                continue;
            }
            if (isSameStorageColumn(rule)) {
                // 密文直接覆盖原字段时，* 已经返回该列；再次前置 `col AS col` 没有收益，
                // 还会把裸 * 场景拖入 `expr, *` / `expr, table.*` 的方言兼容风险。
                registerProjectedKey(projectedKeys, tableReference, rule.column());
                registerProjectedKey(wildcardInjectedKeys, tableReference, rule.column());
                if (registerUnqualifiedKey) {
                    registerProjectedKey(projectedKeys, null, rule.column());
                    registerProjectedKey(wildcardInjectedKeys, null, rule.column());
                }
                continue;
            }
            // `select phone, *` / `select u.phone, u.*` 场景下，显式列已经声明过逻辑别名时，
            // wildcard 只补剩余缺失的加密列，避免重复别名破坏结果集读取顺序。
            if (containsProjectedKey(projectedKeys, tableReference, rule.column())) {
                continue;
            }
            Column sourceColumn = new Column(rule.column());
            if (tableReference != null && StringUtils.isNotBlank(tableReference.getName())) {
                sourceColumn.setTable(new Table(tableReference.getName()));
            }
            ColumnResolution resolution = new ColumnResolution(sourceColumn, rule, true);
            SelectItem<?> storageItem = buildSelectStorageItem(new SelectItem<>(sourceColumn), resolution);
            target.add(storageItem);
            registerProjectedKey(projectedKeys, tableReference, rule.column());
            registerProjectedKey(wildcardInjectedKeys, tableReference, rule.column());
            if (registerUnqualifiedKey) {
                // 无表别名的 `*` 会把列暴露成裸列名；同时登记无前缀 key，后续 `phone`
                // 或 `u.phone` 才能与已经注入的逻辑列对上，避免“看起来不同、实际同列”的重复输出。
                registerProjectedKey(projectedKeys, null, rule.column());
                registerProjectedKey(wildcardInjectedKeys, null, rule.column());
            }
            appended = true;
            if (projectionMode == ProjectionMode.DERIVED) {
                appendDerivedHelperSelectItems(target, storageItem, resolution);
            }
        }
        return appended;
    }

    private void throwUnsupportedBareWildcard() {
        throw new UnsupportedEncryptedOperationException(
                EncryptionErrorCode.UNSUPPORTED_ENCRYPTED_SELECT,
                "Bare wildcard select is not supported in multi-table or derived queries that contain encrypted table rules. "
                        + "Use explicit table wildcards such as `table.*` or `alias.*` instead of `*`."
        );
    }

    private boolean isSameStorageColumn(EncryptColumnRule rule) {
        return NameUtils.normalizeIdentifier(rule.column())
                .equals(NameUtils.normalizeIdentifier(rule.storageColumn()));
    }

    private void registerProjectedKey(Set<String> projectedKeys, SelectItem<?> item) {
        String label = projectedLabel(item);
        if (StringUtils.isBlank(label)) {
            return;
        }
        Expression expression = item.getExpression();
        if (expression instanceof Column) {
            registerProjectedKey(projectedKeys, ((Column) expression).getTable(), label);
            if (((Column) expression).getTable() == null || StringUtils.isBlank(((Column) expression).getTable().getName())) {
                registerProjectedKey(projectedKeys, null, label);
            }
            return;
        }
        registerProjectedKey(projectedKeys, null, label);
    }

    private void registerProjectedKey(Set<String> projectedKeys, Table tableReference, String label) {
        if (StringUtils.isBlank(label)) {
            return;
        }
        projectedKeys.add(projectedKey(tableReference, label));
    }

    private boolean containsProjectedKey(Set<String> projectedKeys, Table tableReference, String label) {
        if (StringUtils.isBlank(label)) {
            return false;
        }
        if (projectedKeys.contains(projectedKey(tableReference, label))) {
            return true;
        }
        return tableReference != null && StringUtils.isNotBlank(tableReference.getName())
                && projectedKeys.contains(projectedKey(null, label));
    }

    private String projectedKey(Table tableReference, String label) {
        String normalizedTable = tableReference == null ? "" : NameUtils.normalizeIdentifier(tableReference.getName());
        String normalizedLabel = NameUtils.normalizeIdentifier(label);
        return normalizedTable + "#" + normalizedLabel;
    }

    private String projectedLabel(SelectItem<?> item) {
        if (item.getAlias() != null && StringUtils.isNotBlank(item.getAlias().getName())) {
            return item.getAlias().getName();
        }
        Expression expression = item.getExpression();
        if (expression instanceof Column) {
            return ((Column) expression).getColumnName();
        }
        return null;
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

    private Table resolveImplicitWildcardTable(PlainSelect plainSelect) {
        if (!(plainSelect.getFromItem() instanceof Table) || plainSelect.getJoins() != null && !plainSelect.getJoins().isEmpty()) {
            return null;
        }
        Table fromTable = (Table) plainSelect.getFromItem();
        String visibleName = fromTable.getAlias() != null && StringUtils.isNotBlank(fromTable.getAlias().getName())
                ? fromTable.getAlias().getName()
                : fromTable.getName();
        return StringUtils.isBlank(visibleName) ? null : new Table(visibleName);
    }

    private Table resolveWildcardExpansionTable(PlainSelect plainSelect) {
        if (!(plainSelect.getFromItem() instanceof Table) || plainSelect.getJoins() != null && !plainSelect.getJoins().isEmpty()) {
            return null;
        }
        Table fromTable = (Table) plainSelect.getFromItem();
        if (fromTable.getAlias() == null || StringUtils.isBlank(fromTable.getAlias().getName())) {
            return null;
        }
        // 单表别名 + 裸 * 场景下，自动补的逻辑列也要带同一个别名，
        // 否则 `u.phone, *` 会变成 `u.phone_cipher AS phone, phone_cipher AS phone, u.*`。
        return new Table(fromTable.getAlias().getName());
    }

    private boolean containsBareWildcard(PlainSelect plainSelect) {
        if (plainSelect.getSelectItems() == null) {
            return false;
        }
        for (SelectItem<?> item : plainSelect.getSelectItems()) {
            if (item.getExpression() instanceof AllColumns && !(item.getExpression() instanceof AllTableColumns)) {
                return true;
            }
        }
        return false;
    }
}
