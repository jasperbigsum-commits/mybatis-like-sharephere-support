package io.github.jasper.mybatis.encrypt.core.rewrite;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.config.SqlDialect;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptTableRule;
import io.github.jasper.mybatis.encrypt.exception.EncryptionConfigurationException;
import io.github.jasper.mybatis.encrypt.exception.EncryptionErrorCode;
import io.github.jasper.mybatis.encrypt.exception.UnsupportedEncryptedOperationException;
import io.github.jasper.mybatis.encrypt.util.JSqlParserSupport;
import io.github.jasper.mybatis.encrypt.util.StringUtils;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.statement.update.Update;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * SQL 改写引擎。
 *
 * <p>负责解析 MyBatis SQL，并在执行前改写加密字段、辅助查询字段和 LIKE 辅助字段；
 * 对不支持的查询语义、排序和存在歧义的查询场景会快速失败；少数被显式放宽的技术值
 * 查询语义会记录 warning，提醒结果不代表明文业务语义。</p>
 */
public class SqlRewriteEngine {

    private static final Logger log = LoggerFactory.getLogger(SqlRewriteEngine.class);

    private final EncryptMetadataRegistry metadataRegistry;
    private final DatabaseEncryptionProperties properties;
    private final ParameterValueResolver parameterValueResolver = new ParameterValueResolver();
    private final SqlLogMasker sqlLogMasker = new SqlLogMasker();
    private final EncryptionValueTransformer valueTransformer;
    private final DerivedTableRuleBuilder derivedTableRuleBuilder;
    private final SqlRewriteValidator sqlRewriteValidator = new SqlRewriteValidator();
    private final SqlConditionRewriter sqlConditionRewriter;
    private final SqlWriteExpressionRewriter sqlWriteExpressionRewriter;
    private final SqlSelectProjectionRewriter sqlSelectProjectionRewriter;
    private final SqlInsertRewriter sqlInsertRewriter;
    private final SqlUpdateSetRewriter sqlUpdateSetRewriter;
    private final SqlSelectTableContextBuilder sqlSelectTableContextBuilder;
    private final AlgorithmRegistry algorithmRegistry;

    /**
     * 创建 SQL 改写引擎。
     *
     * @param metadataRegistry 加密元数据注册中心
     * @param algorithmRegistry 算法注册中心
     * @param properties 插件配置属性
     */
    public SqlRewriteEngine(EncryptMetadataRegistry metadataRegistry,
                            AlgorithmRegistry algorithmRegistry,
                            DatabaseEncryptionProperties properties) {
        this.metadataRegistry = metadataRegistry;
        this.properties = properties;
        this.algorithmRegistry = algorithmRegistry;
        this.valueTransformer = new EncryptionValueTransformer(algorithmRegistry);
        this.derivedTableRuleBuilder = new DerivedTableRuleBuilder(metadataRegistry);
        this.sqlConditionRewriter = new SqlConditionRewriter(
                valueTransformer,
                this::buildColumn,
                this::requireAssistedQueryColumn,
                this::requireLikeQueryColumn,
                this::quote,
                this::rewriteSelect,
                this::warnEncryptedRangeComparison
        );
        this.sqlWriteExpressionRewriter = new SqlWriteExpressionRewriter(valueTransformer, sqlConditionRewriter);
        this.sqlSelectProjectionRewriter = new SqlSelectProjectionRewriter(
                this::resolveEncryptedColumn,
                this::buildColumn,
                this::requireAssistedQueryColumn
        );
        this.sqlInsertRewriter = new SqlInsertRewriter(sqlWriteExpressionRewriter, valueTransformer, this::quote,
                algorithmRegistry);
        this.sqlUpdateSetRewriter = new SqlUpdateSetRewriter(
                sqlWriteExpressionRewriter,
                valueTransformer,
                this::buildColumn,
                algorithmRegistry
        );
        this.sqlSelectTableContextBuilder = new SqlSelectTableContextBuilder(
                metadataRegistry,
                derivedTableRuleBuilder,
                this::rewriteSelect
        );
    }

    /**
     * 改写一次 MyBatis 执行请求。
     *
     * @param mappedStatement 当前 mapped statement
     * @param boundSql 原始 SQL 与参数上下文
     * @return 改写结果；当没有命中加密规则时返回未变更结果
     */
    public RewriteResult rewrite(MappedStatement mappedStatement, BoundSql boundSql) {
        metadataRegistry.warmUp(mappedStatement, boundSql.getParameterObject(), boundSql.getSql());
        try {
            Statement statement = JSqlParserSupport.parseStatement(boundSql.getSql());
            SqlRewriteContext context = new SqlRewriteContext(
                    mappedStatement.getConfiguration(), boundSql, parameterValueResolver);
            if (statement instanceof Insert) {
                rewriteInsert((Insert) statement, context);
            } else if (statement instanceof Update) {
                rewriteUpdate((Update) statement, context);
            } else if (statement instanceof Delete) {
                rewriteDelete((Delete) statement, context);
            } else if (statement instanceof Select) {
                rewriteSelect((Select) statement, context);
            }
            if (!context.changed()) {
                return RewriteResult.unchanged();
            }
            String rewrittenSql = statement.toString();
            String maskedSql = sqlLogMasker.mask(rewrittenSql, context.maskedParameters());
            if (properties.isLogMaskedSql() && log.isDebugEnabled()) {
                log.debug("Encrypted SQL rewrite applied for statement [{}]: {}",
                        mappedStatement.getId(), maskedSql);
            }
            return new RewriteResult(true, rewrittenSql, context.parameterMappings(), context.maskedParameters(), maskedSql);
        } catch (UnsupportedEncryptedOperationException ex) {
            if (log.isDebugEnabled()) {
                log.debug("Encrypted SQL rewrite rejected for statement [{}]: {}",
                        mappedStatement.getId(), ex.getMessage());
            }
            throw ex;
        } catch (Exception ex) {
            if (properties.isFailOnMissingRule()) {
                throw new EncryptionConfigurationException(EncryptionErrorCode.SQL_REWRITE_FAILED,
                        "Failed to rewrite encrypted SQL: " + boundSql.getSql(), ex);
            }
            if (log.isDebugEnabled()) {
                log.debug("Encrypted SQL rewrite skipped for statement [{}] because failOnMissingRule=false: {}",
                        mappedStatement.getId(), ex.getMessage(), ex);
            }
            return RewriteResult.unchanged();
        }
    }

    /**
     * 改写 `INSERT` 语句中的逻辑加密列。
     *
     * <p>同表模式下，逻辑列会被替换为密文列，并按规则追加辅助查询列、LIKE 查询列。
     * 独立表模式下，主表仍保留逻辑列本身，但参数值会被替换成写前准备好的外部引用 id。</p>
     */
    private void rewriteInsert(Insert insert, SqlRewriteContext context) {
        EncryptTableRule tableRule = metadataRegistry.findByTable(insert.getTable().getName()).orElse(null);
        if (tableRule == null) {
            return;
        }
        if (sqlInsertRewriter.rewrite(insert, tableRule, context)) {
            context.markChanged();
        }
    }

    /**
     * 改写 `UPDATE` 语句。
     *
     * <p>`SET` 子句负责把业务明文写成存储态值，`WHERE` 子句则统一走查询态列改写。
     * 两段逻辑故意拆开，便于排查“写入值不对”和“查询条件不命中”这两类问题。</p>
     */
    private void rewriteUpdate(Update update, SqlRewriteContext context) {
        SqlTableContext tableContext = new SqlTableContext();
        sqlSelectTableContextBuilder.registerTable(tableContext, update.getTable());
        if (tableContext.isEmpty()) {
            return;
        }
        if (sqlUpdateSetRewriter.rewrite(update, tableContext, context)) {
            context.markChanged();
        }
        // 只有 WHERE 子句会被改写到查询辅助列，SET 子句仍然写入主密文列。
        update.setWhere(sqlConditionRewriter.rewrite(update.getWhere(), tableContext, context));
    }

    /**
     * 改写 `DELETE` 的条件部分。
     *
     * <p>删除操作不会改写列清单，只允许在条件里使用受支持的加密字段比较语义。
     * 遇到不安全或不可靠的条件时，会在条件递归阶段直接失败。</p>
     */
    private void rewriteDelete(Delete delete, SqlRewriteContext context) {
        SqlTableContext tableContext = new SqlTableContext();
        sqlSelectTableContextBuilder.registerTable(tableContext, delete.getTable());
        if (tableContext.isEmpty()) {
            return;
        }
        delete.setWhere(sqlConditionRewriter.rewrite(delete.getWhere(), tableContext, context));
    }

    private void rewriteSelect(Select select, SqlRewriteContext context) {
        rewriteSelect(select, context, ProjectionMode.NORMAL, null);
    }

    /**
     * 改写 `SELECT` 语句。
     *
     * <p>这里同时处理三件事：注册当前查询块可见的表与别名、递归改写条件和子查询、
     * 以及按 `ProjectionMode` 决定投影列展开方式。复杂 SQL 排查时，先确认当前查询块
     * 落在哪个投影模式，通常能更快定位问题是在投影阶段还是条件阶段。</p>
     *
     * <p>`WITH` 查询会在当前查询块建表上下文前先处理 CTE body。普通 CTE 按命名派生表
     * 暴露投影元数据；递归 CTE 只有在递归体引用加密字段时才拒绝，完全不涉及加密字段的
     * 递归查询保持透传，避免把业务侧的组织树、菜单树等普通递归查询误伤。</p>
     */
    private void rewriteSelect(Select select, SqlRewriteContext context, ProjectionMode projectionMode) {
        rewriteSelect(select, context, projectionMode, null);
    }

    private void rewriteSelect(Select select,
                               SqlRewriteContext context,
                               ProjectionMode projectionMode,
                               SqlTableContext outerTableContext) {
        validateWithItems(select, context, outerTableContext);
        if (select instanceof SetOperationList) {
            SetOperationList setOperationList = (SetOperationList) select;
            for (Select child : setOperationList.getSelects()) {
                rewriteSelect(child, context, projectionMode, outerTableContext);
            }
            return;
        }
        if (select instanceof ParenthesedSelect) {
            ParenthesedSelect parenthesedSelect = (ParenthesedSelect) select;
            if (parenthesedSelect.getSelect() != null) {
                rewriteSelect(parenthesedSelect.getSelect(), context, projectionMode, outerTableContext);
            }
            return;
        }
        if (!(select instanceof PlainSelect)) {
            throw new UnsupportedEncryptedOperationException(EncryptionErrorCode.UNSUPPORTED_ENCRYPTED_SELECT,
                    "Only plain select and set-operation select are supported for encrypted SQL rewrite.");
        }
        PlainSelect plainSelect = (PlainSelect) select;
        SqlTableContext tableContext = sqlSelectTableContextBuilder.build(plainSelect, context, outerTableContext);
        // SQL 参数绑定顺序遵循语句中的占位符出现顺序；SELECT 列表中的参数需要先消费。
        sqlConditionRewriter.consumeSelectItemParameters(plainSelect.getSelectItems(), context);
        rewriteJoinConditions(plainSelect, tableContext, context);
        plainSelect.setWhere(sqlConditionRewriter.rewrite(plainSelect.getWhere(), tableContext, context));
        plainSelect.setHaving(sqlConditionRewriter.rewrite(plainSelect.getHaving(), tableContext, context));
        plainSelect.setQualify(sqlConditionRewriter.rewrite(plainSelect.getQualify(), tableContext, context));
        if (rewriteAggregateExpressions(plainSelect, tableContext)) {
            context.markChanged();
        }
        if (rewriteOrderByExpressions(plainSelect.getOrderByElements(), tableContext)) {
            context.markChanged();
        }
        if (tableContext.isEmpty()) {
            return;
        }
        if (rewriteWindowPartitionExpressions(plainSelect, tableContext)) {
            context.markChanged();
        }
        if (rewriteGroupBy(plainSelect.getGroupBy(), tableContext)) {
            context.markChanged();
        }
        sqlRewriteValidator.validateSelect(plainSelect, tableContext);
        if (sqlSelectProjectionRewriter.rewrite(plainSelect, tableContext, projectionMode)) {
            context.markChanged();
        }
    }

    /**
     * 校验当前查询块声明的 CTE。
     *
     * <p>非递归 CTE 会交给后续表上下文构建逻辑按派生表改写。递归 CTE 的自引用会让
     * “上一轮投影出的逻辑列”和“当前轮真实表字段”混在同一名称空间里，因此只要递归体
     * 触碰加密字段就 fail fast；若递归体只使用普通字段，则不需要加密改写参与，允许放行。</p>
     *
     * @param select 当前查询块
     * @param context 当前 SQL 改写上下文
     * @param outerTableContext 外层查询块可见的表上下文，供相关子查询解析外层引用
     */
    private void validateWithItems(Select select, SqlRewriteContext context, SqlTableContext outerTableContext) {
        if (select.getWithItemsList() == null) {
            return;
        }
        for (WithItem withItem : select.getWithItemsList()) {
            if (withItem == null) {
                continue;
            }
            if (withItem.isRecursive() && containsEncryptedReferenceInRecursiveWithItem(withItem, context, outerTableContext)) {
                throw new UnsupportedEncryptedOperationException(EncryptionErrorCode.UNSUPPORTED_ENCRYPTED_SELECT,
                        "WITH RECURSIVE referencing encrypted fields is not supported for encrypted SQL rewrite.");
            }
            if (withItem.getSelect() != null) {
                validateWithItems(withItem.getSelect(), context, outerTableContext);
            }
        }
    }

    /**
     * 判断递归 CTE body 是否引用了加密字段。
     *
     * <p>这里是只读扫描，不做任何 AST 改写，也不消费参数槽位；它只回答“是否需要拒绝”
     * 这个安全问题。真正的普通 CTE 改写仍由 {@link SqlSelectTableContextBuilder} 统一处理。</p>
     *
     * @param withItem 当前 CTE 定义
     * @param context 当前 SQL 改写上下文
     * @param outerTableContext 外层查询块表上下文
     * @return 若递归 CTE body 引用加密字段则返回 {@code true}
     */
    private boolean containsEncryptedReferenceInRecursiveWithItem(WithItem withItem,
                                                                  SqlRewriteContext context,
                                                                  SqlTableContext outerTableContext) {
        if (withItem.getSelect() == null) {
            return false;
        }
        return containsEncryptedReferenceInSelect(withItem.getSelect(), context, outerTableContext);
    }

    /**
     * 递归扫描一个 SELECT 树，判断其中是否存在可解析到加密规则的列引用。
     *
     * <p>扫描范围覆盖投影、WHERE、JOIN ON、HAVING、QUALIFY、ORDER BY、GROUP BY 以及
     * UNION 分支。它故意复用表上下文构建器，以保证 CTE、派生表和相关子查询里的列解析
     * 与实际改写路径保持一致。</p>
     *
     * @param select 待扫描 SELECT
     * @param context 当前 SQL 改写上下文
     * @param outerTableContext 外层查询块表上下文
     * @return 若 SELECT 树引用加密字段则返回 {@code true}
     */
    private boolean containsEncryptedReferenceInSelect(Select select,
                                                       SqlRewriteContext context,
                                                       SqlTableContext outerTableContext) {
        if (select == null) {
            return false;
        }
        if (select instanceof ParenthesedSelect) {
            return containsEncryptedReferenceInSelect(((ParenthesedSelect) select).getSelect(), context, outerTableContext);
        }
        if (select instanceof SetOperationList) {
            SetOperationList setOperationList = (SetOperationList) select;
            for (Select child : setOperationList.getSelects()) {
                if (containsEncryptedReferenceInSelect(child, context, outerTableContext)) {
                    return true;
                }
            }
            return false;
        }
        if (!(select instanceof PlainSelect)) {
            return false;
        }
        PlainSelect plainSelect = (PlainSelect) select;
        SqlTableContext tableContext = sqlSelectTableContextBuilder.build(plainSelect, context, outerTableContext);
        if (containsEncryptedReferenceInSelectItems(plainSelect.getSelectItems(), tableContext, context)
                || containsEncryptedReference(plainSelect.getWhere(), tableContext, context)
                || containsEncryptedReference(plainSelect.getHaving(), tableContext, context)
                || containsEncryptedReference(plainSelect.getQualify(), tableContext, context)
                || containsEncryptedReferenceInOrderBy(plainSelect.getOrderByElements(), tableContext, context)
                || containsEncryptedReferenceInGroupBy(plainSelect.getGroupBy(), tableContext, context)) {
            return true;
        }
        return containsEncryptedReferenceInJoins(plainSelect.getJoins(), tableContext, context);
    }

    private boolean containsEncryptedReferenceInSelectItems(List<SelectItem<?>> selectItems,
                                                            SqlTableContext tableContext,
                                                            SqlRewriteContext context) {
        if (selectItems == null) {
            return false;
        }
        for (SelectItem<?> item : selectItems) {
            if (item != null && containsEncryptedReference(item.getExpression(), tableContext, context)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsEncryptedReferenceInJoins(List<Join> joins,
                                                      SqlTableContext tableContext,
                                                      SqlRewriteContext context) {
        if (joins == null) {
            return false;
        }
        for (Join join : joins) {
            if (join.getOnExpressions() == null) {
                continue;
            }
            for (Expression expression : join.getOnExpressions()) {
                if (containsEncryptedReference(expression, tableContext, context)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean containsEncryptedReferenceInOrderBy(List<OrderByElement> orderByElements,
                                                        SqlTableContext tableContext,
                                                        SqlRewriteContext context) {
        if (orderByElements == null) {
            return false;
        }
        for (OrderByElement element : orderByElements) {
            if (element != null && containsEncryptedReference(element.getExpression(), tableContext, context)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("rawtypes")
    private boolean containsEncryptedReferenceInGroupBy(GroupByElement groupByElement,
                                                        SqlTableContext tableContext,
                                                        SqlRewriteContext context) {
        if (groupByElement == null || groupByElement.getGroupByExpressionList() == null) {
            return false;
        }
        ExpressionList expressionList = groupByElement.getGroupByExpressionList();
        for (Object item : expressionList) {
            if (item instanceof Expression && containsEncryptedReference((Expression) item, tableContext, context)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 扫描表达式树，判断是否包含加密字段引用。
     *
     * <p>该方法只做保守识别，覆盖常见表达式容器和嵌套 SELECT。它不尝试解释业务语义，
     * 只要某个 {@link Column} 能通过当前 {@link SqlTableContext} 解析到加密规则，就认为
     * 当前递归 CTE 不适合继续自动改写。</p>
     *
     * @param expression 待扫描表达式
     * @param tableContext 当前表达式所在查询块的表上下文
     * @param context 当前 SQL 改写上下文
     * @return 若表达式引用加密字段则返回 {@code true}
     */
    @SuppressWarnings("rawtypes")
    private boolean containsEncryptedReference(Expression expression,
                                               SqlTableContext tableContext,
                                               SqlRewriteContext context) {
        if (expression == null) {
            return false;
        }
        if (resolveEncryptedColumn(expression, tableContext) != null) {
            return true;
        }
        if (expression instanceof Parenthesis) {
            return containsEncryptedReference(((Parenthesis) expression).getExpression(), tableContext, context);
        }
        if (expression instanceof BinaryExpression) {
            BinaryExpression binaryExpression = (BinaryExpression) expression;
            return containsEncryptedReference(binaryExpression.getLeftExpression(), tableContext, context)
                    || containsEncryptedReference(binaryExpression.getRightExpression(), tableContext, context);
        }
        if (expression instanceof Function && ((Function) expression).getParameters() != null) {
            for (Expression item : ((Function) expression).getParameters()) {
                if (containsEncryptedReference(item, tableContext, context)) {
                    return true;
                }
            }
            return false;
        }
        if (expression instanceof ParenthesedExpressionList) {
            ParenthesedExpressionList parenthesed = (ParenthesedExpressionList) expression;
            for (Object item : parenthesed) {
                if (item instanceof Expression && containsEncryptedReference((Expression) item, tableContext, context)) {
                    return true;
                }
            }
            return false;
        }
        if (expression instanceof CaseExpression) {
            CaseExpression caseExpression = (CaseExpression) expression;
            if (containsEncryptedReference(caseExpression.getSwitchExpression(), tableContext, context)
                    || containsEncryptedReference(caseExpression.getElseExpression(), tableContext, context)) {
                return true;
            }
            if (caseExpression.getWhenClauses() != null) {
                for (WhenClause whenClause : caseExpression.getWhenClauses()) {
                    if (containsEncryptedReference(whenClause.getWhenExpression(), tableContext, context)
                            || containsEncryptedReference(whenClause.getThenExpression(), tableContext, context)) {
                        return true;
                    }
                }
            }
            return false;
        }
        if (expression instanceof NotExpression) {
            return containsEncryptedReference(((NotExpression) expression).getExpression(), tableContext, context);
        }
        if (expression instanceof IsNullExpression) {
            return containsEncryptedReference(((IsNullExpression) expression).getLeftExpression(), tableContext, context);
        }
        if (expression instanceof Between) {
            Between between = (Between) expression;
            return containsEncryptedReference(between.getLeftExpression(), tableContext, context)
                    || containsEncryptedReference(between.getBetweenExpressionStart(), tableContext, context)
                    || containsEncryptedReference(between.getBetweenExpressionEnd(), tableContext, context);
        }
        if (expression instanceof InExpression) {
            InExpression inExpression = (InExpression) expression;
            return containsEncryptedReference(inExpression.getLeftExpression(), tableContext, context)
                    || containsEncryptedReference(inExpression.getRightExpression(), tableContext, context);
        }
        if (expression instanceof Select) {
            return context != null && containsEncryptedReferenceInSelect((Select) expression, context, tableContext);
        }
        return false;
    }

    private void rewriteJoinConditions(PlainSelect plainSelect, SqlTableContext tableContext, SqlRewriteContext context) {
        if (plainSelect.getJoins() == null) {
            return;
        }
        for (Join join : plainSelect.getJoins()) {
            Collection<Expression> onExpressions = join.getOnExpressions();
            if (onExpressions == null || onExpressions.isEmpty()) {
                continue;
            }
            List<Expression> rewrittenExpressions = new ArrayList<>(onExpressions.size());
            for (Expression onExpression : onExpressions) {
                // JOIN ON is part of the same query block, so it must follow the same encrypted-column rules as WHERE.
                rewrittenExpressions.add(sqlConditionRewriter.rewrite(onExpression, tableContext, context));
            }
            join.setOnExpressions(rewrittenExpressions);
        }
    }

    private boolean rewriteAggregateExpressions(PlainSelect plainSelect, SqlTableContext tableContext) {
        boolean changed = false;
        if (plainSelect.getSelectItems() != null) {
            for (SelectItem<?> item : plainSelect.getSelectItems()) {
                if (rewriteAggregateExpression(item.getExpression(), tableContext)) {
                    changed = true;
                }
            }
        }
        if (rewriteAggregateExpression(plainSelect.getHaving(), tableContext)) {
            changed = true;
        }
        if (rewriteAggregateExpression(plainSelect.getQualify(), tableContext)) {
            changed = true;
        }
        return changed;
    }

    private boolean rewriteOrderByExpressions(List<OrderByElement> orderByElements, SqlTableContext tableContext) {
        if (orderByElements == null) {
            return false;
        }
        boolean changed = false;
        for (OrderByElement element : orderByElements) {
            Expression expression = element.getExpression();
            ColumnResolution resolution = resolveEncryptedColumn(expression, tableContext);
            if (resolution == null) {
                continue;
            }
            warnEncryptedOrderBy(resolution.rule());
            Expression rewritten = rewriteOrderByExpression(resolution);
            if (rewritten != expression) {
                element.setExpression(rewritten);
                changed = true;
            }
        }
        return changed;
    }

    private Expression rewriteOrderByExpression(ColumnResolution resolution) {
        EncryptColumnRule rule = resolution.rule();
        if (!rule.hasAssistedQueryColumn()) {
            throw new UnsupportedEncryptedOperationException(EncryptionErrorCode.UNSUPPORTED_ENCRYPTED_ORDER_BY,
                    "ORDER BY on encrypted field requires assistedQueryColumn. " + describeRule(rule));
        }
        if (rule.isStoredInSeparateTable()) {
            return buildColumn(resolution.column(), rule.column());
        }
        return buildColumn(resolution.column(), rule.assistedQueryColumn());
    }

    private void warnEncryptedOrderBy(EncryptColumnRule rule) {
        if (!log.isWarnEnabled()) {
            return;
        }
        log.warn("ORDER BY on encrypted field [{}] is allowed for technical sorting only; results are ordered by "
                        + "hash/reference values and may not match plaintext business order.",
                rule.property());
    }

    private void warnEncryptedRangeComparison(EncryptColumnRule rule) {
        if (!log.isWarnEnabled()) {
            return;
        }
        log.warn("Single-sided range comparison on encrypted field [{}] is allowed for technical cursor semantics only; "
                        + "same-table fields use assisted/hash values and separate-table fields use reference values, "
                        + "so the result does not represent plaintext business ordering.",
                rule.property());
    }

    private boolean rewriteAggregateExpression(Expression expression, SqlTableContext tableContext) {
        if (expression == null) {
            return false;
        }
        if (expression instanceof Function) {
            return rewriteAggregateFunction((Function) expression, tableContext);
        }
        if (expression instanceof Parenthesis) {
            return rewriteAggregateExpression(((Parenthesis) expression).getExpression(), tableContext);
        }
        if (expression instanceof BinaryExpression) {
            BinaryExpression binaryExpression = (BinaryExpression) expression;
            boolean changed = rewriteAggregateExpression(binaryExpression.getLeftExpression(), tableContext);
            if (rewriteAggregateExpression(binaryExpression.getRightExpression(), tableContext)) {
                changed = true;
            }
            return changed;
        }
        if (expression instanceof ParenthesedExpressionList) {
            boolean changed = false;
            ParenthesedExpressionList<?> parenthesed = (ParenthesedExpressionList<?>) expression;
            for (Expression item : parenthesed) {
                if (rewriteAggregateExpression(item, tableContext)) {
                    changed = true;
                }
            }
            return changed;
        }
        if (expression instanceof CaseExpression) {
            CaseExpression caseExpression = (CaseExpression) expression;
            boolean changed = rewriteAggregateExpression(caseExpression.getSwitchExpression(), tableContext);
            if (rewriteAggregateExpression(caseExpression.getElseExpression(), tableContext)) {
                changed = true;
            }
            if (caseExpression.getWhenClauses() != null) {
                for (WhenClause whenClause : caseExpression.getWhenClauses()) {
                    if (rewriteAggregateExpression(whenClause.getWhenExpression(), tableContext)
                            || rewriteAggregateExpression(whenClause.getThenExpression(), tableContext)) {
                        changed = true;
                    }
                }
            }
            return changed;
        }
        if (expression instanceof NotExpression) {
            return rewriteAggregateExpression(((NotExpression) expression).getExpression(), tableContext);
        }
        return false;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private boolean rewriteAggregateFunction(Function function, SqlTableContext tableContext) {
        boolean changed = false;
        if (isRewriteableTechnicalAggregate(function) && function.getParameters() != null) {
            ExpressionList parameters = function.getParameters();
            for (int index = 0; index < parameters.size(); index++) {
                Object item = parameters.get(index);
                if (!(item instanceof Expression)) {
                    continue;
                }
                Expression expression = (Expression) item;
                Expression rewritten = rewriteAggregateOperand(function, expression, tableContext);
                if (rewritten != expression) {
                    parameters.set(index, rewritten);
                    changed = true;
                }
                if (rewriteAggregateExpression(rewritten, tableContext)) {
                    changed = true;
                }
            }
        } else if (function.getParameters() != null) {
            for (Expression item : function.getParameters()) {
                if (rewriteAggregateExpression(item, tableContext)) {
                    changed = true;
                }
            }
        }
        return changed;
    }

    private Expression rewriteAggregateOperand(Function function, Expression expression, SqlTableContext tableContext) {
        if (expression instanceof Parenthesis) {
            Parenthesis parenthesis = (Parenthesis) expression;
            Expression rewritten = rewriteAggregateOperand(function, parenthesis.getExpression(), tableContext);
            if (rewritten != parenthesis.getExpression()) {
                parenthesis.setExpression(rewritten);
            }
            return parenthesis;
        }
        ColumnResolution resolution = resolveEncryptedColumn(expression, tableContext);
        if (resolution == null) {
            return expression;
        }
        if (isSupportedTechnicalAggregate(function)) {
            warnEncryptedTechnicalAggregate(function, resolution.rule());
        }
        if (resolution.rule().isStoredInSeparateTable()) {
            return expression;
        }
        if (isSupportedTechnicalAggregate(function)) {
            requireAssistedQueryColumn(resolution.rule(), aggregateScenario(function));
            return buildColumn(resolution.column(), resolution.rule().storageColumn());
        }
        return buildColumn(resolution.column(), requireAssistedQueryColumn(resolution.rule(), "COUNT aggregate"));
    }

    private boolean isCountAggregate(Function function) {
        String name = function.getName();
        return name != null && "COUNT".equals(name.toUpperCase(java.util.Locale.ROOT));
    }

    private boolean isRewriteableTechnicalAggregate(Function function) {
        return isCountAggregate(function) || isSupportedTechnicalAggregate(function);
    }

    private boolean isSupportedTechnicalAggregate(Function function) {
        String name = function.getName();
        if (name == null) {
            return false;
        }
        String upperName = name.toUpperCase(java.util.Locale.ROOT);
        return "MAX".equals(upperName) || "FIRST".equals(upperName);
    }

    private String aggregateScenario(Function function) {
        String name = function.getName();
        return (name == null ? "aggregate" : name.toUpperCase(java.util.Locale.ROOT) + " aggregate");
    }

    private void warnEncryptedTechnicalAggregate(Function function, EncryptColumnRule rule) {
        if (!log.isWarnEnabled()) {
            return;
        }
        log.warn("{} on encrypted field [{}] is allowed for technical aggregation only; same-table fields use "
                        + "ciphertext values and separate-table fields use reference values, so the result may not match "
                        + "plaintext business semantics.",
                function.getName(), rule.property());
    }

    private boolean rewriteWindowPartitionExpressions(PlainSelect plainSelect, SqlTableContext tableContext) {
        boolean changed = false;
        List<SelectItem<?>> selectItems = plainSelect.getSelectItems();
        if (selectItems != null) {
            for (SelectItem<?> item : selectItems) {
                if (rewriteWindowPartitionExpressions(item.getExpression(), tableContext)) {
                    changed = true;
                }
            }
        }
        if (plainSelect.getWindowDefinitions() != null) {
            for (WindowDefinition windowDefinition : plainSelect.getWindowDefinitions()) {
                if (rewriteWindowPartitionExpressions(windowDefinition, tableContext)) {
                    changed = true;
                }
            }
        }
        return changed;
    }

    private boolean rewriteWindowPartitionExpressions(Expression expression, SqlTableContext tableContext) {
        if (expression == null) {
            return false;
        }
        if (expression instanceof AnalyticExpression) {
            AnalyticExpression analyticExpression = (AnalyticExpression) expression;
            boolean changed = rewriteWindowPartitionList(analyticExpression.getPartitionExpressionList(), tableContext);
            if (analyticExpression.getWindowDefinition() != null
                    && rewriteWindowPartitionExpressions(analyticExpression.getWindowDefinition(), tableContext)) {
                changed = true;
            }
            return changed;
        }
        if (expression instanceof Parenthesis) {
            return rewriteWindowPartitionExpressions(((Parenthesis) expression).getExpression(), tableContext);
        }
        if (expression instanceof BinaryExpression) {
            BinaryExpression binaryExpression = (BinaryExpression) expression;
            return rewriteWindowPartitionExpressions(binaryExpression.getLeftExpression(), tableContext)
                    || rewriteWindowPartitionExpressions(binaryExpression.getRightExpression(), tableContext);
        }
        if (expression instanceof Function && ((Function) expression).getParameters() != null) {
            boolean changed = false;
            for (Expression item : ((Function) expression).getParameters()) {
                if (rewriteWindowPartitionExpressions(item, tableContext)) {
                    changed = true;
                }
            }
            return changed;
        }
        if (expression instanceof CaseExpression) {
            CaseExpression caseExpression = (CaseExpression) expression;
            boolean changed = rewriteWindowPartitionExpressions(caseExpression.getSwitchExpression(), tableContext)
                    || rewriteWindowPartitionExpressions(caseExpression.getElseExpression(), tableContext);
            if (caseExpression.getWhenClauses() != null) {
                for (WhenClause whenClause : caseExpression.getWhenClauses()) {
                    if (rewriteWindowPartitionExpressions(whenClause.getWhenExpression(), tableContext)
                            || rewriteWindowPartitionExpressions(whenClause.getThenExpression(), tableContext)) {
                        changed = true;
                    }
                }
            }
            return changed;
        }
        if (expression instanceof NotExpression) {
            return rewriteWindowPartitionExpressions(((NotExpression) expression).getExpression(), tableContext);
        }
        return false;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private boolean rewriteWindowPartitionList(List partitionExpressions, SqlTableContext tableContext) {
        if (partitionExpressions == null) {
            return false;
        }
        boolean changed = false;
        for (int index = 0; index < partitionExpressions.size(); index++) {
            Object item = partitionExpressions.get(index);
            if (!(item instanceof Expression)) {
                continue;
            }
            Expression expression = (Expression) item;
            Expression rewritten = rewriteWindowPartitionExpression(expression, tableContext);
            if (rewritten != expression) {
                partitionExpressions.set(index, rewritten);
                changed = true;
            }
        }
        return changed;
    }

    private boolean rewriteWindowPartitionExpressions(WindowDefinition windowDefinition, SqlTableContext tableContext) {
        if (windowDefinition == null) {
            return false;
        }
        return rewriteWindowPartitionList(windowDefinition.getPartitionExpressionList(), tableContext);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private boolean rewriteGroupBy(GroupByElement groupByElement, SqlTableContext tableContext) {
        if (groupByElement == null || groupByElement.getGroupByExpressionList() == null) {
            return false;
        }
        boolean changed = false;
        ExpressionList expressionList = groupByElement.getGroupByExpressionList();
        for (int index = 0; index < expressionList.size(); index++) {
            Object item = expressionList.get(index);
            if (!(item instanceof Expression)) {
                continue;
            }
            Expression rewritten = rewriteGroupByExpression((Expression) item, tableContext);
            if (rewritten != item) {
                expressionList.set(index, rewritten);
                changed = true;
            }
        }
        return changed;
    }

    private Expression rewriteGroupByExpression(Expression expression, SqlTableContext tableContext) {
        ColumnResolution resolution = resolveEncryptedColumn(expression, tableContext);
        if (resolution == null) {
            return expression;
        }
        EncryptColumnRule rule = resolution.rule();
        // GROUP BY 只支持简单加密列。复杂表达式继续交给校验器拒绝，避免 hash 后语义不等价。
        String targetColumn = rule.isStoredInSeparateTable()
                ? rule.column()
                : requireAssistedQueryColumn(rule, "GROUP BY");
        return buildColumn(resolution.column(), targetColumn);
    }

    private Expression rewriteWindowPartitionExpression(Expression expression, SqlTableContext tableContext) {
        ColumnResolution resolution = resolveEncryptedColumn(expression, tableContext);
        if (resolution == null) {
            return expression;
        }
        EncryptColumnRule rule = resolution.rule();
        String targetColumn = rule.isStoredInSeparateTable()
                ? rule.column()
                : requireAssistedQueryColumn(rule, "WINDOW PARTITION BY");
        return buildColumn(resolution.column(), targetColumn);
    }

    private String requireAssistedQueryColumn(EncryptColumnRule rule, String scenario) {
        if (!rule.hasAssistedQueryColumn()) {
            throw new UnsupportedEncryptedOperationException(EncryptionErrorCode.MISSING_ASSISTED_QUERY_COLUMN,
                    "Encrypted " + scenario + " requires assistedQueryColumn. " + describeRule(rule));
        }
        return rule.assistedQueryColumn();
    }

    private String requireLikeQueryColumn(EncryptColumnRule rule, String scenario) {
        if (!rule.hasLikeQueryColumn()) {
            throw new UnsupportedEncryptedOperationException(EncryptionErrorCode.MISSING_LIKE_QUERY_COLUMN,
                    "Encrypted " + scenario + " requires likeQueryColumn. " + describeRule(rule));
        }
        return rule.likeQueryColumn();
    }

    private String describeRule(EncryptColumnRule rule) {
        String table = StringUtils.isNotBlank(rule.table()) ? rule.table() : "<entity-default-table>";
        if (rule.isStoredInSeparateTable()) {
            return "property=" + rule.property()
                    + ", table=" + table
                    + ", column=" + rule.column()
                    + ", storageTable=" + rule.storageTable();
        }
        return "property=" + rule.property()
                + ", table=" + table
                + ", column=" + rule.column();
    }

    private ColumnResolution resolveEncryptedColumn(Expression expression, SqlTableContext tableContext) {
        if (!(expression instanceof Column)) {
            return null;
        }
        Column column = (Column) expression;
        EncryptColumnRule rule = tableContext.resolve(column).orElse(null);
        return rule == null ? null : new ColumnResolution(column, rule, true);
    }

    private Column buildColumn(Column source, String targetColumn) {
        Column column = new Column(quote(targetColumn));
        if (source.getTable() != null && source.getTable().getName() != null) {
            // 这里只保留表引用名（表名或别名），避免生成类似 "t AS t.`col`" 的非法 SQL。
            column.setTable(new Table(source.getTable().getName()));
        }
        return column;
    }

    private String quote(String identifier) {
        SqlDialect dialect = properties.getSqlDialect();
        return dialect == null ? identifier : dialect.quote(identifier);
    }
}
