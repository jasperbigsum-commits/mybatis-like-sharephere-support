package io.github.jasper.mybatis.encrypt.core.rewrite;

import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.update.UpdateSet;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.session.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.algorithm.AssistedQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.CipherAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.LikeQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.config.SqlDialect;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptTableRule;
import io.github.jasper.mybatis.encrypt.core.metadata.FieldStorageMode;
import io.github.jasper.mybatis.encrypt.exception.EncryptionConfigurationException;
import io.github.jasper.mybatis.encrypt.exception.UnsupportedEncryptedOperationException;
import io.github.jasper.mybatis.encrypt.util.NameUtils;

import java.util.*;

/**
 * SQL 改写引擎。
 *
 * <p>负责解析 MyBatis SQL，并在执行前改写加密字段、辅助查询字段和 LIKE 辅助字段；
 * 对不支持的范围查询、排序和存在歧义的查询场景会快速失败。</p>
 */
public class SqlRewriteEngine {

    private static final Logger log = LoggerFactory.getLogger(SqlRewriteEngine.class);

    private final EncryptMetadataRegistry metadataRegistry;
    private final AlgorithmRegistry algorithmRegistry;
    private final DatabaseEncryptionProperties properties;
    private final ParameterValueResolver parameterValueResolver = new ParameterValueResolver();
    private final SqlLogMasker sqlLogMasker = new SqlLogMasker();

    public SqlRewriteEngine(EncryptMetadataRegistry metadataRegistry,
                            AlgorithmRegistry algorithmRegistry,
                            DatabaseEncryptionProperties properties) {
        this.metadataRegistry = metadataRegistry;
        this.algorithmRegistry = algorithmRegistry;
        this.properties = properties;
    }

    /**
     * 改写一次 MyBatis 执行请求。
     *
     * @param mappedStatement 当前 mapped statement
     * @param boundSql 原始 SQL 与参数上下文
     * @return 改写结果；当没有命中加密规则时返回未变更结果
     */
    public RewriteResult rewrite(MappedStatement mappedStatement, BoundSql boundSql) {
        metadataRegistry.warmUp(mappedStatement, boundSql.getParameterObject());
        try {
            Statement statement = CCJSqlParserUtil.parse(boundSql.getSql());
            RewriteContext context = new RewriteContext(mappedStatement.getConfiguration(), boundSql);
            if (statement instanceof Insert insert) {
                rewriteInsert(insert, context);
            } else if (statement instanceof Update update) {
                rewriteUpdate(update, context);
            } else if (statement instanceof Delete delete) {
                rewriteDelete(delete, context);
            } else if (statement instanceof Select select) {
                rewriteSelect(select, context);
            }
            if (!context.changed) {
                return RewriteResult.unchanged();
            }
            String rewrittenSql = statement.toString();
            String maskedSql = sqlLogMasker.mask(rewrittenSql, context.maskedParameters);
            if (properties.isLogMaskedSql() && log.isDebugEnabled()) {
                log.debug("Encrypted SQL rewrite applied for statement [{}]: {}",
                        mappedStatement.getId(), maskedSql);
            }
            return new RewriteResult(true, rewrittenSql, context.parameterMappings, context.maskedParameters, maskedSql);
        } catch (UnsupportedEncryptedOperationException ex) {
            if (log.isDebugEnabled()) {
                log.debug("Encrypted SQL rewrite rejected for statement [{}]: {}",
                        mappedStatement.getId(), ex.getMessage());
            }
            throw ex;
        } catch (Exception ex) {
            if (properties.isFailOnMissingRule()) {
                throw new EncryptionConfigurationException("Failed to rewrite encrypted SQL: " + boundSql.getSql(), ex);
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
    private void rewriteInsert(Insert insert, RewriteContext context) {
        EncryptTableRule tableRule = metadataRegistry.findByTable(insert.getTable().getName()).orElse(null);
        if (tableRule == null) {
            return;
        }
        Values values = insert.getValues();
        if (values == null || values.getExpressions() == null) {
            throw new UnsupportedEncryptedOperationException("Only VALUES inserts are supported for encrypted tables.");
        }
        ExpressionList<?> originalExpressions = values.getExpressions();
        List<Column> originalColumns = new ArrayList<>(insert.getColumns());
        List<Column> rewrittenColumns = new ArrayList<>();
        List<Expression> rewrittenExpressions = new ArrayList<>();
        for (int index = 0; index < originalColumns.size(); index++) {
            Column column = originalColumns.get(index);
            Expression expression = originalExpressions.get(index);
            EncryptColumnRule rule = tableRule.findByColumn(column.getColumnName()).orElse(null);
            if (rule == null) {
                rewrittenColumns.add(column);
                rewrittenExpressions.add(passthroughWriteExpression(expression, context));
                continue;
            }
            if (rule.isStoredInSeparateTable()) {
                rewrittenColumns.add(column);
                rewrittenExpressions.add(rewriteSeparateTableReferenceExpression(expression, context));
                continue;
            }
            rewrittenColumns.add(new Column(quote(rule.storageColumn())));
            WriteValue writeValue = rewriteEncryptedWriteExpression(expression, rule, context);
            rewrittenExpressions.add(writeValue.expression());
            if (rule.hasAssistedQueryColumn()) {
                rewrittenColumns.add(new Column(quote(rule.assistedQueryColumn())));
                rewrittenExpressions.add(buildShadowExpression(writeValue, transformAssisted(rule, writeValue.plainValue()),
                        MaskingMode.HASH, context));
            }
            if (rule.hasLikeQueryColumn()) {
                rewrittenColumns.add(new Column(quote(rule.likeQueryColumn())));
                rewrittenExpressions.add(buildShadowExpression(writeValue, transformLike(rule, writeValue.plainValue()),
                        MaskingMode.MASKED, context));
            }
        }
        insert.setColumns(new ExpressionList<>(rewrittenColumns));
        values.setExpressions(new ParenthesedExpressionList<>(rewrittenExpressions));
        context.changed = true;
    }

    /**
     * 改写 `UPDATE` 语句。
     *
     * <p>`SET` 子句负责把业务明文写成存储态值，`WHERE` 子句则统一走查询态列改写。
     * 两段逻辑故意拆开，便于排查“写入值不对”和“查询条件不命中”这两类问题。</p>
     */
    private void rewriteUpdate(Update update, RewriteContext context) {
        TableContext tableContext = new TableContext();
        registerTable(tableContext, update.getTable());
        if (tableContext.isEmpty()) {
            return;
        }
        for (UpdateSet updateSet : update.getUpdateSets()) {
            List<Column> originalColumns = new ArrayList<>(updateSet.getColumns());
            ExpressionList<Expression> updateValues = (ExpressionList<Expression>) updateSet.getValues();
            List<Column> rewrittenColumns = new ArrayList<>();
            List<Expression> rewrittenExpressions = new ArrayList<>();
            for (int index = 0; index < originalColumns.size(); index++) {
                Column column = originalColumns.get(index);
                Expression expression = updateValues.get(index);
                EncryptColumnRule rule = tableContext.resolve(column).orElse(null);
                if (rule == null) {
                    rewrittenColumns.add(column);
                    rewrittenExpressions.add(passthroughWriteExpression(expression, context));
                    continue;
                }

                if (rule.isStoredInSeparateTable()) {
                    rewrittenColumns.add(column);
                    rewrittenExpressions.add(rewriteSeparateTableReferenceExpression(expression, context));
                    continue;
                }
                rewrittenColumns.add(buildColumn(column, rule.storageColumn()));
                WriteValue writeValue = rewriteEncryptedWriteExpression(expression, rule, context);
                rewrittenExpressions.add(writeValue.expression());
                if (rule.hasAssistedQueryColumn()) {
                    rewrittenColumns.add(buildColumn(column, rule.assistedQueryColumn()));
                    rewrittenExpressions.add(buildShadowExpression(writeValue, transformAssisted(rule, writeValue.plainValue()),
                            MaskingMode.HASH, context));
                }
                if (rule.hasLikeQueryColumn()) {
                    rewrittenColumns.add(buildColumn(column, rule.likeQueryColumn()));
                    rewrittenExpressions.add(buildShadowExpression(writeValue, transformLike(rule, writeValue.plainValue()),
                            MaskingMode.MASKED, context));
                }
            }
            updateSet.getColumns().clear();
            updateSet.getColumns().addAll(rewrittenColumns);
            updateValues.clear();
            updateValues.addAll(rewrittenExpressions);
        }
        // 只有 WHERE 子句会被改写到查询辅助列，SET 子句仍然写入主密文列。
        update.setWhere(rewriteCondition(update.getWhere(), tableContext, context));
    }

    /**
     * 改写 `DELETE` 的条件部分。
     *
     * <p>删除操作不会改写列清单，只允许在条件里使用受支持的加密字段比较语义。
     * 遇到不安全或不可靠的条件时，会在条件递归阶段直接失败。</p>
     */
    private void rewriteDelete(Delete delete, RewriteContext context) {
        TableContext tableContext = new TableContext();
        registerTable(tableContext, delete.getTable());
        if (tableContext.isEmpty()) {
            return;
        }
        delete.setWhere(rewriteCondition(delete.getWhere(), tableContext, context));
    }

    private void rewriteSelect(Select select, RewriteContext context) {
        rewriteSelect(select, context, ProjectionMode.NORMAL);
    }

    /**
     * 改写 `SELECT` 语句。
     *
     * <p>这里同时处理三件事：注册当前查询块可见的表与别名、递归改写条件和子查询、
     * 以及按 `ProjectionMode` 决定投影列展开方式。复杂 SQL 排查时，先确认当前查询块
     * 落在哪个投影模式，通常能更快定位问题是在投影阶段还是条件阶段。</p>
     */
    private void rewriteSelect(Select select, RewriteContext context, ProjectionMode projectionMode) {
        if (select instanceof SetOperationList setOperationList) {
            for (Select child : setOperationList.getSelects()) {
                rewriteSelect(child, context, projectionMode);
            }
            return;
        }
        if (select instanceof ParenthesedSelect parenthesedSelect) {
            if (parenthesedSelect.getSelect() != null) {
                rewriteSelect(parenthesedSelect.getSelect(), context, projectionMode);
            }
            return;
        }
        if (!(select instanceof PlainSelect plainSelect)) {
            throw new UnsupportedEncryptedOperationException("Only plain select and set-operation select are supported for encrypted SQL rewrite.");
        }
        TableContext tableContext = new TableContext();
        registerFromItem(tableContext, plainSelect.getFromItem(), context);
        if (plainSelect.getJoins() != null) {
            for (Join join : plainSelect.getJoins()) {
                registerFromItem(tableContext, join.getRightItem(), context);
            }
        }
        plainSelect.setWhere(rewriteCondition(plainSelect.getWhere(), tableContext, context));
        plainSelect.setHaving(rewriteCondition(plainSelect.getHaving(), tableContext, context));
        plainSelect.setQualify(rewriteCondition(plainSelect.getQualify(), tableContext, context));
        if (tableContext.isEmpty()) {
            return;
        }
        validateDistinct(plainSelect.getDistinct(), plainSelect.getSelectItems(), tableContext);
        validateAggregateExpressions(plainSelect.getSelectItems(), plainSelect.getHaving(), plainSelect.getQualify(), tableContext);
        if (projectionMode == ProjectionMode.COMPARISON) {
            rewriteComparisonSelectItems(plainSelect, tableContext);
        } else {
            rewriteSelectItems(plainSelect, tableContext, projectionMode, context);
        }
        validateAnalyticExpressions(plainSelect.getSelectItems(), tableContext);
        validateWindowDefinitions(plainSelect.getWindowDefinitions(), tableContext);
        validateGroupBy(plainSelect.getGroupBy(), tableContext);
        validateOrderBy(plainSelect.getOrderByElements(), tableContext);
    }

    /**
     * 递归改写条件表达式树。
     *
     * <p>这是排查查询条件问题的主入口。它统一处理 `WHERE`、`HAVING`、`QUALIFY`、
     * `CASE WHEN`、`EXISTS` 子查询以及括号嵌套。某类表达式如果没有在这里被正确消费或改写，
     * 往往会进一步表现为参数槽位错位或条件未命中。</p>
     *
     * <p>JSqlParser 5.x 仍然使用带括号的表达式列表来表示部分分组条件，因此需要显式递归。</p>
     */
    private Expression rewriteCondition(Expression expression, TableContext tableContext, RewriteContext context) {
        if (expression == null) {
            return null;
        }
        if (expression instanceof ParenthesedExpressionList parenthesis) {
            parenthesis.replaceAll(o -> {
                if (o instanceof Expression exp) {
                    rewriteCondition(exp, tableContext, context);
                }
                return o;
            });
            return parenthesis;
        }
        if (expression instanceof ExistsExpression existsExpression) {
            if (existsExpression.getRightExpression() instanceof Select subquery) {
                rewriteSelect(subquery, context);
            }
            return existsExpression;
        }
        if (expression instanceof NotExpression notExpression) {
            notExpression.setExpression(rewriteCondition(notExpression.getExpression(), tableContext, context));
            return notExpression;
        }
        if (expression instanceof CaseExpression caseExpression) {
            if (caseExpression.getSwitchExpression() != null) {
                caseExpression.setSwitchExpression(rewriteCondition(caseExpression.getSwitchExpression(), tableContext, context));
            }
            if (caseExpression.getWhenClauses() != null) {
                for (WhenClause whenClause : caseExpression.getWhenClauses()) {
                    whenClause.setWhenExpression(rewriteCondition(whenClause.getWhenExpression(), tableContext, context));
                    whenClause.setThenExpression(rewriteCondition(whenClause.getThenExpression(), tableContext, context));
                }
            }
            if (caseExpression.getElseExpression() != null) {
                caseExpression.setElseExpression(rewriteCondition(caseExpression.getElseExpression(), tableContext, context));
            }
            return caseExpression;
        }
        if (expression instanceof Select select) {
            rewriteSelect(select, context);
            return expression;
        }
        if (expression instanceof AndExpression andExpression) {
            andExpression.setLeftExpression(rewriteCondition(andExpression.getLeftExpression(), tableContext, context));
            andExpression.setRightExpression(rewriteCondition(andExpression.getRightExpression(), tableContext, context));
            return andExpression;
        }
        if (expression instanceof OrExpression orExpression) {
            orExpression.setLeftExpression(rewriteCondition(orExpression.getLeftExpression(), tableContext, context));
            orExpression.setRightExpression(rewriteCondition(orExpression.getRightExpression(), tableContext, context));
            return orExpression;
        }
        if (expression instanceof EqualsTo equalsTo) {
            return rewriteEquality(equalsTo, tableContext, context);
        }
        if (expression instanceof NotEqualsTo notEqualsTo) {
            return rewriteEquality(notEqualsTo, tableContext, context);
        }
        if (expression instanceof LikeExpression likeExpression) {
            return rewriteLikeCondition(likeExpression, tableContext, context);
        }
        if (expression instanceof InExpression inExpression) {
            return rewriteInCondition(inExpression, tableContext, context);
        }
        if (expression instanceof IsNullExpression isNullExpression) {
            return rewriteIsNullCondition(isNullExpression, tableContext, context);
        }
        if (expression instanceof Between between) {
            validateNonRangeEncryptedColumn(between.getLeftExpression(), tableContext,
                    "BETWEEN is not supported on encrypted fields.");
            consumeExpression(between.getBetweenExpressionStart(), context);
            consumeExpression(between.getBetweenExpressionEnd(), context);
            return between;
        }
        if (expression instanceof GreaterThan || expression instanceof GreaterThanEquals
                || expression instanceof MinorThan || expression instanceof MinorThanEquals) {
            BinaryExpression binaryExpression = (BinaryExpression) expression;
            validateNonRangeEncryptedColumn(binaryExpression.getLeftExpression(), tableContext,
                    "Range comparison is not supported on encrypted fields.");
            consumeExpression(binaryExpression.getRightExpression(), context);
            return expression;
        }
        if (expression instanceof BinaryExpression binaryExpression) {
            binaryExpression.setLeftExpression(rewriteCondition(binaryExpression.getLeftExpression(), tableContext, context));
            binaryExpression.setRightExpression(rewriteCondition(binaryExpression.getRightExpression(), tableContext, context));
            return binaryExpression;
        }
        if (expression instanceof Function function && function.getParameters() != null) {
            for (Expression item : function.getParameters()) {
                rewriteCondition(item, tableContext, context);
            }
            return expression;
        }
        if (expression instanceof JdbcParameter) {
            context.consumeOriginal();
        }
        return expression;
    }

    /**
     * 改写等值与不等值比较。
     *
     * <p>同表模式优先落到辅助查询列；独立表模式则改写成 `EXISTS` 子查询，
     * 通过主表逻辑列中保存的引用 id 去关联独立表记录。</p>
     */
    private Expression rewriteEquality(BinaryExpression expression, TableContext tableContext, RewriteContext context) {
        ColumnResolution resolution = resolveComparison(expression, tableContext);
        if (resolution == null) {
            expression.setLeftExpression(rewriteCondition(expression.getLeftExpression(), tableContext, context));
            expression.setRightExpression(rewriteCondition(expression.getRightExpression(), tableContext, context));
            return expression;
        }
        EncryptColumnRule rule = resolution.rule();
        if (rule.isStoredInSeparateTable()) {
            return rewriteSeparateTableCondition(resolution, expression.getRightExpression(), context,
                    rule.assistedQueryColumn(), true);
        }
        String targetColumn = rule.hasAssistedQueryColumn() ? rule.assistedQueryColumn() : rule.storageColumn();
        if (resolution.leftColumn()) {
            expression.setLeftExpression(buildColumn(resolution.column(), targetColumn));
            rewriteOperand(expression.getRightExpression(), context,
                    rule.hasAssistedQueryColumn()
                            ? transformAssisted(rule, readOperandValue(expression.getRightExpression(), context))
                            : transformCipher(rule, readOperandValue(expression.getRightExpression(), context)),
                    rule.hasAssistedQueryColumn() ? MaskingMode.HASH : MaskingMode.MASKED);
        } else {
            expression.setRightExpression(buildColumn(resolution.column(), targetColumn));
            rewriteOperand(expression.getLeftExpression(), context,
                    rule.hasAssistedQueryColumn()
                            ? transformAssisted(rule, readOperandValue(expression.getLeftExpression(), context))
                            : transformCipher(rule, readOperandValue(expression.getLeftExpression(), context)),
                    rule.hasAssistedQueryColumn() ? MaskingMode.HASH : MaskingMode.MASKED);
        }
        context.changed = true;
        return expression;
    }

    /**
     * 改写 `LIKE` 条件。
     *
     * <p>同表模式要求配置 `likeQueryColumn`；独立表模式会改写成外层 `EXISTS`，
     * 其中关联条件仍然是“独立表主键 = 主表逻辑列中的引用 id”。</p>
     */
    private Expression rewriteLikeCondition(LikeExpression expression, TableContext tableContext, RewriteContext context) {
        ColumnResolution resolution = resolveEncryptedColumn(expression.getLeftExpression(), tableContext);
        if (resolution == null) {
            expression.setLeftExpression(rewriteCondition(expression.getLeftExpression(), tableContext, context));
            expression.setRightExpression(rewriteCondition(expression.getRightExpression(), tableContext, context));
            return expression;
        }
        EncryptColumnRule rule = resolution.rule();
        if (rule.isStoredInSeparateTable()) {
            return rewriteSeparateTableCondition(resolution, expression.getRightExpression(), context,
                    rule.likeQueryColumn(), false);
        }
        if (!rule.hasLikeQueryColumn()) {
            throw new UnsupportedEncryptedOperationException(
                    "LIKE query requires likeQueryColumn for encrypted field: " + rule.property());
        }
        expression.setLeftExpression(buildColumn(resolution.column(), rule.likeQueryColumn()));
        rewriteOperand(expression.getRightExpression(), context,
                transformLike(rule, readOperandValue(expression.getRightExpression(), context)), MaskingMode.MASKED);
        context.changed = true;
        return expression;
    }

    /**
     * 改写 `IN` / `NOT IN` 条件。
     *
     * <p>字面量列表和子查询比较都会走这里，但独立表字段目前明确不支持 `IN`，
     * 因为它需要额外的 `EXISTS` 展开或 join 语义，参数顺序也更容易失真。</p>
     */
    private Expression rewriteInCondition(InExpression expression, TableContext tableContext, RewriteContext context) {
        ColumnResolution resolution = resolveEncryptedColumn(expression.getLeftExpression(), tableContext);
        if (resolution == null) {
            if (expression.getRightExpression() instanceof Select subquery) {
                rewriteSelect(subquery, context);
            }
            consumeItemsList(expression.getRightExpression(), context);
            return expression;
        }
        EncryptColumnRule rule = resolution.rule();
        if (rule.isStoredInSeparateTable()) {
            throw new UnsupportedEncryptedOperationException("IN query is not supported for separate-table encrypted field: "
                    + rule.property());
        }
        // IN 查询与等值查询使用同一套目标列选择策略。
        String targetColumn = rule.hasAssistedQueryColumn() ? rule.assistedQueryColumn() : rule.storageColumn();
        expression.setLeftExpression(buildColumn(resolution.column(), targetColumn));
        if (expression.getRightExpression() instanceof Select subquery) {
            rewriteSelect(subquery, context, ProjectionMode.COMPARISON);
            context.changed = true;
            return expression;
        }
        if (!(expression.getRightExpression() instanceof ExpressionList<?> expressionList)) {
            throw new UnsupportedEncryptedOperationException("Unsupported IN operand for encrypted fields.");
        }
        for (Expression item : expressionList) {
            rewriteOperand(item, context,
                    rule.hasAssistedQueryColumn() ? transformAssisted(rule, readOperandValue(item, context))
                            : transformCipher(rule, readOperandValue(item, context)),
                    rule.hasAssistedQueryColumn() ? MaskingMode.HASH : MaskingMode.MASKED);
        }
        context.changed = true;
        return expression;
    }

    /**
     * 改写 `IS NULL` / `IS NOT NULL`。
     *
     * <p>同表模式判断密文列本身；独立表模式判断的是主表中的引用 id 是否能在独立表中找到记录，
     * 因而会被改写成存在性子查询。</p>
     */
    private Expression rewriteIsNullCondition(IsNullExpression expression, TableContext tableContext, RewriteContext context) {
        ColumnResolution resolution = resolveEncryptedColumn(expression.getLeftExpression(), tableContext);
        if (resolution == null) {
            expression.setLeftExpression(rewriteCondition(expression.getLeftExpression(), tableContext, context));
            return expression;
        }
        EncryptColumnRule rule = resolution.rule();
        if (rule.isStoredInSeparateTable()) {
            context.changed = true;
            return buildExistsPresenceSubQuery(resolution.column(), rule, expression.isNot());
        }
        expression.setLeftExpression(buildColumn(resolution.column(), rule.storageColumn()));
        context.changed = true;
        return expression;
    }

    private void rewriteOperand(Expression expression, RewriteContext context, String transformedValue, MaskingMode maskingMode) {
        if (expression instanceof JdbcParameter) {
            context.replaceLastConsumed(transformedValue, maskingMode);
            return;
        }
        if (expression instanceof StringValue stringValue) {
            stringValue.setValue(transformedValue);
            return;
        }
        if (expression instanceof NullValue) {
            return;
        }
        throw new UnsupportedEncryptedOperationException("Encrypted query condition must use prepared parameter or string literal.");
    }

    /**
     * 把独立表字段条件改写成 `EXISTS` 子查询。
     *
     * <p>这个方法统一处理独立表等值和 LIKE 两种查询。它先把业务明文转换成查询态值，
     * 再同步重建参数绑定，最后生成带有“引用 id 关联条件”的 `EXISTS`。</p>
     */
    private Expression rewriteSeparateTableCondition(ColumnResolution resolution,
                                                     Expression operand,
                                                     RewriteContext context,
                                                     String targetColumn,
                                                     boolean assisted) {
        EncryptColumnRule rule = resolution.rule();
        if (targetColumn == null || targetColumn.isBlank()) {
            throw new UnsupportedEncryptedOperationException(
                    "Separate-table encrypted field requires query column: " + rule.property());
        }
        String transformed = assisted
                ? transformAssisted(rule, readOperandValue(operand, context))
                : transformLike(rule, readOperandValue(operand, context));
        replaceOperandBinding(operand, context, transformed, assisted ? MaskingMode.HASH : MaskingMode.MASKED);
        return buildExistsSubQuery(resolution.column(), rule, targetColumn, buildQueryValueExpression(operand, transformed), assisted);
    }

    private void replaceOperandBinding(Expression operand, RewriteContext context, String transformed, MaskingMode maskingMode) {
        if (operand instanceof JdbcParameter) {
            context.replaceLastConsumed(transformed, maskingMode);
            return;
        }
        if (operand instanceof StringValue || operand instanceof LongValue || operand instanceof NullValue) {
            return;
        }
        throw new UnsupportedEncryptedOperationException("Separate-table encrypted query must use prepared parameter or literal.");
    }

    /**
     * 改写写路径上的业务明文表达式。
     *
     * <p>它只负责生成主密文列对应的值，不直接处理辅助列和 LIKE 列。
     * 后两者由调用方基于返回的 `WriteValue` 再决定是否追加影子参数。</p>
     */
    private WriteValue rewriteEncryptedWriteExpression(Expression expression,
                                                       EncryptColumnRule rule,
                                                       RewriteContext context) {
        Object plainValue = readOperandValue(expression, context);
        String cipherValue = transformCipher(rule, plainValue);
        if (expression instanceof JdbcParameter) {
            context.replaceLastConsumed(cipherValue, MaskingMode.MASKED);
            return new WriteValue(expression, plainValue, true);
        }
        if (expression instanceof StringValue stringValue) {
            stringValue.setValue(cipherValue);
            return new WriteValue(stringValue, plainValue, false);
        }
        if (expression instanceof LongValue) {
            // 当表达式是LongValue ?
            return new WriteValue(new StringValue(cipherValue), plainValue, false);
        }
        if (expression instanceof NullValue) {
            return new WriteValue(expression, null, false);
        }
        throw new UnsupportedEncryptedOperationException("Encrypted write only supports prepared parameters or string literals.");
    }

    private Expression passthroughWriteExpression(Expression expression, RewriteContext context) {
        consumeExpression(expression, context);
        return expression;
    }

    /**
     * 改写独立表字段在主表写 SQL 中对应的参数。
     *
     * <p>独立表模式下，主表逻辑列写入的不再是业务明文，而是写前阶段已经准备好的独立表引用 id。
     * 这里会把原参数槽位替换成该引用值，并同步重建参数映射类型，避免运行时仍按原业务字段类型绑定。</p>
     */
    private Expression rewriteSeparateTableReferenceExpression(Expression expression, RewriteContext context) {
        if (expression instanceof JdbcParameter) {
            int parameterIndex = context.consumeOriginal();
            Object referenceId = context.originalValue(parameterIndex, parameterValueResolver);
            context.replaceParameter(parameterIndex, referenceId, MaskingMode.MASKED);
            return expression;
        }
        consumeExpression(expression, context);
        return expression;
    }

    private Expression buildShadowExpression(WriteValue writeValue, String value, MaskingMode maskingMode, RewriteContext context) {
        if (value == null) {
            return new NullValue();
        }
        if (writeValue.parameterized()) {
            return context.insertSynthetic(value, maskingMode);
        }
        return new StringValue(value);
    }

    private Object readOperandValue(Expression expression, RewriteContext context) {
        if (expression instanceof JdbcParameter) {
            int parameterIndex = context.consumeOriginal();
            return context.originalValue(parameterIndex, parameterValueResolver);
        }
        if (expression instanceof StringValue stringValue) {
            return stringValue.getValue();
        }
        if (expression instanceof LongValue longValue) {
            return longValue.getStringValue();
        }
        return null;
    }

    private void consumeExpression(Expression expression, RewriteContext context) {
        if (expression == null) {
            return;
        }
        if (expression instanceof JdbcParameter) {
            context.consumeOriginal();
            return;
        }
        if (expression instanceof ParenthesedExpressionList parenthesis) {
            parenthesis.forEach(o -> {
                if (o instanceof Expression exp) {
                    consumeExpression(exp, context);
                }
            });
            return;
        }
        if (expression instanceof BinaryExpression binaryExpression) {
            consumeExpression(binaryExpression.getLeftExpression(), context);
            consumeExpression(binaryExpression.getRightExpression(), context);
            return;
        }
        if (expression instanceof Function function && function.getParameters() != null) {
            for (Expression item : function.getParameters()) {
                consumeExpression(item, context);
            }
        }
    }

    private void consumeItemsList(Object itemsList, RewriteContext context) {
        if (itemsList instanceof ExpressionList<?> expressionList) {
            for (Expression item : expressionList) {
                consumeExpression(item, context);
            }
        }
    }

    private Expression buildQueryValueExpression(Expression operand, String transformed) {
        if (operand instanceof JdbcParameter) {
            return new JdbcParameter();
        }
        if (operand instanceof StringValue) {
            return transformed == null ? new NullValue() : new StringValue(transformed);
        }
        if (operand instanceof LongValue) {
            return transformed == null ? new NullValue() : new StringValue(transformed);
        }
        if (operand instanceof NullValue) {
            return new NullValue();
        }
        throw new UnsupportedEncryptedOperationException("Separate-table encrypted query must use prepared parameter or literal.");
    }

    /**
     * 改写普通 `SELECT` 的投影列表。
     *
     * <p>`NORMAL` 模式输出业务可见的逻辑列别名；`DERIVED` 模式会额外注入隐藏的辅助列别名，
     * 供外层查询继续按逻辑字段名改写条件。独立表字段不会在这里展开，因为它依赖结果回填阶段解密。</p>
     */
    private void rewriteSelectItems(PlainSelect plainSelect,
                                    TableContext tableContext,
                                    ProjectionMode projectionMode,
                                    RewriteContext context) {
        if (plainSelect.getSelectItems() == null) {
            return;
        }
        List<SelectItem<?>> rewritten = new ArrayList<>();
        for (SelectItem<?> item : plainSelect.getSelectItems()) {
            Expression expression = item.getExpression();
            if (expression instanceof AllTableColumns allTableColumns) {
                rewritten.add(item);
                appendSelectAliasesForWildcard(rewritten, tableContext.rulesForSelectExpansion(allTableColumns.getTable()),
                        allTableColumns.getTable(), projectionMode);
                context.changed = true;
                continue;
            }
            if (expression instanceof AllColumns) {
                rewritten.add(item);
                appendSelectAliasesForWildcard(rewritten, tableContext.rulesForSelectExpansion(null), null, projectionMode);
                context.changed = true;
                continue;
            }
            ColumnResolution resolution = resolveEncryptedColumn(expression, tableContext);
            if (resolution == null) {
                rewritten.add(item);
                continue;
            }
            if (resolution.rule().isStoredInSeparateTable()) {
                rewritten.add(item);
                context.changed = true;
                continue;
            }
            rewritten.add(buildSelectStorageItem(item, resolution));
            context.changed = true;
            if (projectionMode == ProjectionMode.DERIVED) {
                appendDerivedHelperSelectItems(rewritten, item, resolution);
                context.changed = true;
            }
        }
        if (!rewritten.isEmpty()) {
            plainSelect.setSelectItems(rewritten);
        }
    }

    /**
     * 改写 `IN (subquery)` 右侧子查询的投影列表。
     *
     * <p>比较型子查询不能保留业务逻辑列语义，只能输出真正参与比较的物理列，
     * 否则外层 `IN` 左右两侧含义会不一致。</p>
     */
    private void rewriteComparisonSelectItems(PlainSelect plainSelect, TableContext tableContext) {
        if (plainSelect.getSelectItems() == null) {
            return;
        }
        List<SelectItem<?>> rewritten = new ArrayList<>();
        for (SelectItem<?> item : plainSelect.getSelectItems()) {
            Expression expression = item.getExpression();
            if (expression instanceof AllColumns) {
                throw new UnsupportedEncryptedOperationException("Wildcard select is not supported in encrypted IN subquery.");
            }
            ColumnResolution resolution = resolveEncryptedColumn(expression, tableContext);
            if (resolution == null) {
                rewritten.add(item);
                continue;
            }
            if (resolution.rule().isStoredInSeparateTable()) {
                throw new UnsupportedEncryptedOperationException("Separate-table encrypted field is not supported in IN subquery.");
            }
            rewritten.add(buildComparisonSelectItem(item, resolution));
        }
        if (!rewritten.isEmpty()) {
            plainSelect.setSelectItems(rewritten);
        }
    }

    private void appendSelectAliasesForWildcard(List<SelectItem<?>> target,
                                                List<EncryptColumnRule> rules,
                                                Table tableReference,
                                                ProjectionMode projectionMode) {
        for (EncryptColumnRule rule : rules) {
            if (rule.isStoredInSeparateTable()) {
                continue;
            }
            Column sourceColumn = new Column(rule.column());
            if (tableReference != null && tableReference.getName() != null && !tableReference.getName().isBlank()) {
                sourceColumn.setTable(new Table(tableReference.getName()));
            }
            SelectItem<?> storageItem = buildSelectStorageItem(new SelectItem<>(sourceColumn), new ColumnResolution(sourceColumn, rule, true));
            target.add(storageItem);
            if (projectionMode == ProjectionMode.DERIVED) {
                appendDerivedHelperSelectItems(target, storageItem, new ColumnResolution(sourceColumn, rule, true));
            }
        }
    }

    private void appendDerivedHelperSelectItems(List<SelectItem<?>> target,
                                                SelectItem<?> originalItem,
                                                ColumnResolution resolution) {
        String logicalAlias = selectAliasName(originalItem, resolution);
        if (resolution.rule().hasAssistedQueryColumn()) {
            target.add(SelectItem.from(
                    buildColumn(resolution.column(), resolution.rule().assistedQueryColumn()),
                    new Alias(hiddenAssistedAlias(logicalAlias), false)
            ));
        }
        if (resolution.rule().hasLikeQueryColumn()) {
            target.add(SelectItem.from(
                    buildColumn(resolution.column(), resolution.rule().likeQueryColumn()),
                    new Alias(hiddenLikeAlias(logicalAlias), false)
            ));
        }
    }

    private SelectItem<?> buildSelectStorageItem(SelectItem<?> originalItem, ColumnResolution resolution) {
        Alias alias = originalItem.getAlias() != null
                ? originalItem.getAlias()
                : new Alias(resolution.column().getColumnName(), false);
        return SelectItem.from(buildColumn(resolution.column(), resolution.rule().storageColumn()), alias);
    }

    private SelectItem<?> buildComparisonSelectItem(SelectItem<?> originalItem, ColumnResolution resolution) {
        EncryptColumnRule rule = resolution.rule();
        String targetColumn = rule.hasAssistedQueryColumn() ? rule.assistedQueryColumn() : rule.storageColumn();
        Alias alias = originalItem.getAlias() != null
                ? originalItem.getAlias()
                : new Alias(resolution.column().getColumnName(), false);
        return SelectItem.from(buildColumn(resolution.column(), targetColumn), alias);
    }

    private String selectAliasName(SelectItem<?> item, ColumnResolution resolution) {
        return item.getAlias() != null && item.getAlias().getName() != null && !item.getAlias().getName().isBlank()
                ? item.getAlias().getName()
                : resolution.column().getColumnName();
    }

    private String hiddenAssistedAlias(String logicalAlias) {
        return "__enc_assisted_" + logicalAlias;
    }

    private String hiddenLikeAlias(String logicalAlias) {
        return "__enc_like_" + logicalAlias;
    }

    private void validateOrderBy(List<OrderByElement> orderByElements, TableContext tableContext) {
        if (orderByElements == null) {
            return;
        }
        for (OrderByElement element : orderByElements) {
            ColumnResolution resolution = resolveEncryptedColumn(element.getExpression(), tableContext);
            if (resolution != null) {
                throw new UnsupportedEncryptedOperationException(
                        "ORDER BY is not supported on encrypted field: " + resolution.rule().property());
            }
        }
    }

    /**
     * 构造独立表等值/LIKE 查询使用的 `EXISTS` 子查询。
     *
     * <p>子查询中始终同时包含两部分谓词：一是“独立表主键 = 主表逻辑列里的引用 id”，
     * 二是目标查询列与转换后查询值的比较。排查独立表条件问题时，这两个条件缺一不可。</p>
     */
    private Expression buildExistsSubQuery(Column sourceColumn,
                                           EncryptColumnRule rule,
                                           String targetColumn,
                                           Expression valueExpression,
                                           boolean equality) {
        PlainSelect subQueryBody = new PlainSelect();
        subQueryBody.addSelectItems(SelectItem.from(new LongValue(1)));
        subQueryBody.setFromItem(new Table(quote(rule.storageTable())));
        EqualsTo joinEquals = new EqualsTo();
        joinEquals.setLeftExpression(new Column(quote(rule.storageIdColumn())));
        joinEquals.setRightExpression(buildColumn(sourceColumn, rule.column()));
        Expression valuePredicate;
        if (equality) {
            EqualsTo valueEquals = new EqualsTo();
            valueEquals.setLeftExpression(new Column(quote(targetColumn)));
            valueEquals.setRightExpression(valueExpression);
            valuePredicate = valueEquals;
        } else {
            LikeExpression likeExpression = new LikeExpression();
            likeExpression.setLeftExpression(new Column(quote(targetColumn)));
            likeExpression.setRightExpression(valueExpression);
            valuePredicate = likeExpression;
        }
        subQueryBody.setWhere(new AndExpression(joinEquals, valuePredicate));
        ExistsExpression existsExpression = new ExistsExpression();
        existsExpression.setRightExpression(new ParenthesedSelect().withSelect(subQueryBody));
        return existsExpression;
    }

    /**
     * 构造独立表 `IS NULL` / `IS NOT NULL` 对应的存在性子查询。
     *
     * <p>这里判断的不是业务明文是否为空，而是主表中保存的引用 id 是否能在独立表中定位到记录。</p>
     */
    private Expression buildExistsPresenceSubQuery(Column sourceColumn,
                                                   EncryptColumnRule rule,
                                                   boolean shouldExist) {
        PlainSelect subQueryBody = new PlainSelect();
        subQueryBody.addSelectItems(SelectItem.from(new LongValue(1)));
        subQueryBody.setFromItem(new Table(quote(rule.storageTable())));
        EqualsTo joinEquals = new EqualsTo();
        joinEquals.setLeftExpression(new Column(quote(rule.storageIdColumn())));
        joinEquals.setRightExpression(buildColumn(sourceColumn, rule.column()));
        subQueryBody.setWhere(joinEquals);
        ExistsExpression existsExpression = new ExistsExpression();
        existsExpression.setRightExpression(new ParenthesedSelect().withSelect(subQueryBody));
        existsExpression.setNot(!shouldExist);
        return existsExpression;
    }

    private void validateNonRangeEncryptedColumn(Expression expression, TableContext tableContext, String message) {
        if (resolveEncryptedColumn(expression, tableContext) != null) {
            throw new UnsupportedEncryptedOperationException(message);
        }
    }

    private ColumnResolution resolveComparison(BinaryExpression expression, TableContext tableContext) {
        ColumnResolution left = resolveEncryptedColumn(expression.getLeftExpression(), tableContext);
        if (left != null) {
            return new ColumnResolution(left.column(), left.rule(), true);
        }
        ColumnResolution right = resolveEncryptedColumn(expression.getRightExpression(), tableContext);
        return right == null ? null : new ColumnResolution(right.column(), right.rule(), false);
    }

    private ColumnResolution resolveEncryptedColumn(Expression expression, TableContext tableContext) {
        if (!(expression instanceof Column column)) {
            return null;
        }
        EncryptColumnRule rule = tableContext.resolve(column).orElse(null);
        return rule == null ? null : new ColumnResolution(column, rule, true);
    }

    private void registerFromItem(TableContext tableContext, FromItem fromItem, RewriteContext context) {
        if (fromItem instanceof Table table) {
            registerTable(tableContext, table);
            return;
        }
        if (fromItem instanceof ParenthesedSelect parenthesedSelect && parenthesedSelect.getSelect() != null) {
            rewriteSelect(parenthesedSelect.getSelect(), context, ProjectionMode.DERIVED);
            if (parenthesedSelect.getAlias() != null && parenthesedSelect.getAlias().getName() != null
                    && !parenthesedSelect.getAlias().getName().isBlank()) {
                EncryptTableRule derivedRule = buildDerivedTableRule(parenthesedSelect.getAlias().getName(),
                        parenthesedSelect.getSelect());
                if (derivedRule != null) {
                    tableContext.registerDerived(parenthesedSelect.getAlias().getName(), derivedRule);
                }
            }
        }
    }

    private void validateDistinct(Distinct distinct, List<SelectItem<?>> selectItems, TableContext tableContext) {
        if (distinct == null || selectItems == null) {
            return;
        }
        for (SelectItem<?> item : selectItems) {
            if (containsEncryptedReference(item.getExpression(), tableContext)) {
                throw new UnsupportedEncryptedOperationException("DISTINCT is not supported on encrypted fields.");
            }
        }
    }

    private void validateAggregateExpressions(List<SelectItem<?>> selectItems,
                                              Expression having,
                                              Expression qualify,
                                              TableContext tableContext) {
        if (selectItems != null) {
            for (SelectItem<?> item : selectItems) {
                if (containsUnsupportedAggregate(item.getExpression(), tableContext)) {
                    throw new UnsupportedEncryptedOperationException(
                            "Aggregate function is not supported on encrypted fields.");
                }
            }
        }
        if (containsUnsupportedAggregate(having, tableContext) || containsUnsupportedAggregate(qualify, tableContext)) {
            throw new UnsupportedEncryptedOperationException(
                    "Aggregate function is not supported on encrypted fields.");
        }
    }

    private void validateAnalyticExpressions(List<SelectItem<?>> selectItems, TableContext tableContext) {
        if (selectItems == null) {
            return;
        }
        for (SelectItem<?> item : selectItems) {
            Expression expression = item.getExpression();
            if (expression instanceof AnalyticExpression analyticExpression
                    && containsEncryptedReference(analyticExpression, tableContext)) {
                throw new UnsupportedEncryptedOperationException(
                        "Window function is not supported on encrypted fields.");
            }
        }
    }

    private void validateWindowDefinitions(List<WindowDefinition> windowDefinitions, TableContext tableContext) {
        if (windowDefinitions == null) {
            return;
        }
        for (WindowDefinition windowDefinition : windowDefinitions) {
            if (containsEncryptedReference(windowDefinition, tableContext)) {
                throw new UnsupportedEncryptedOperationException(
                        "Named window definition is not supported on encrypted fields.");
            }
        }
    }

    private void validateGroupBy(GroupByElement groupByElement, TableContext tableContext) {
        if (groupByElement == null || groupByElement.getGroupByExpressions() == null) {
            return;
        }
        for (Object item : groupByElement.getGroupByExpressions()) {
            if (item instanceof Expression expression && containsEncryptedReference(expression, tableContext)) {
                throw new UnsupportedEncryptedOperationException("GROUP BY is not supported on encrypted fields.");
            }
        }
    }

    private boolean containsEncryptedReference(Expression expression, TableContext tableContext) {
        if (expression == null) {
            return false;
        }
        if (resolveEncryptedColumn(expression, tableContext) != null) {
            return true;
        }
        if (expression instanceof ParenthesedExpressionList parenthesis) {
            for (Object item : parenthesis) {
                if (item instanceof Expression child && containsEncryptedReference(child, tableContext)) {
                    return true;
                }
            }
            return false;
        }
        if (expression instanceof BinaryExpression binaryExpression) {
            return containsEncryptedReference(binaryExpression.getLeftExpression(), tableContext)
                    || containsEncryptedReference(binaryExpression.getRightExpression(), tableContext);
        }
        if (expression instanceof Function function && function.getParameters() != null) {
            for (Expression item : function.getParameters()) {
                if (containsEncryptedReference(item, tableContext)) {
                    return true;
                }
            }
            return false;
        }
        if (expression instanceof CaseExpression caseExpression) {
            if (containsEncryptedReference(caseExpression.getSwitchExpression(), tableContext)) {
                return true;
            }
            if (caseExpression.getWhenClauses() != null) {
                for (WhenClause whenClause : caseExpression.getWhenClauses()) {
                    if (containsEncryptedReference(whenClause.getWhenExpression(), tableContext)
                            || containsEncryptedReference(whenClause.getThenExpression(), tableContext)) {
                        return true;
                    }
                }
            }
            return containsEncryptedReference(caseExpression.getElseExpression(), tableContext);
        }
        if (expression instanceof NotExpression notExpression) {
            return containsEncryptedReference(notExpression.getExpression(), tableContext);
        }
        if (expression instanceof AnalyticExpression analyticExpression) {
            if (containsEncryptedReference(analyticExpression.getExpression(), tableContext)
                    || containsEncryptedReference(analyticExpression.getFilterExpression(), tableContext)
                    || containsEncryptedReference(analyticExpression.getOffset(), tableContext)
                    || containsEncryptedReference(analyticExpression.getDefaultValue(), tableContext)) {
                return true;
            }
            if (analyticExpression.getPartitionExpressionList() != null) {
                for (Object item : analyticExpression.getPartitionExpressionList()) {
                    if (item instanceof Expression child && containsEncryptedReference(child, tableContext)) {
                        return true;
                    }
                }
            }
            if (analyticExpression.getOrderByElements() != null) {
                for (OrderByElement element : analyticExpression.getOrderByElements()) {
                    if (containsEncryptedReference(element.getExpression(), tableContext)) {
                        return true;
                    }
                }
            }
            if (analyticExpression.getWindowDefinition() != null) {
                return containsEncryptedReference(analyticExpression.getWindowDefinition(), tableContext);
            }
            return false;
        }
        return false;
    }

    private boolean containsEncryptedReference(WindowDefinition windowDefinition, TableContext tableContext) {
        if (windowDefinition == null) {
            return false;
        }
        if (windowDefinition.getPartitionExpressionList() != null) {
            for (Object item : windowDefinition.getPartitionExpressionList()) {
                if (item instanceof Expression expression && containsEncryptedReference(expression, tableContext)) {
                    return true;
                }
            }
        }
        if (windowDefinition.getOrderByElements() != null) {
            for (OrderByElement element : windowDefinition.getOrderByElements()) {
                if (containsEncryptedReference(element.getExpression(), tableContext)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean containsUnsupportedAggregate(Expression expression, TableContext tableContext) {
        if (expression == null) {
            return false;
        }
        if (expression instanceof Function function) {
            if (isAggregateFunction(function)) {
                if (function.isAllColumns()) {
                    return false;
                }
                if (function.getParameters() != null) {
                    for (Expression item : function.getParameters()) {
                        if (containsEncryptedReference(item, tableContext) || containsUnsupportedAggregate(item, tableContext)) {
                            return true;
                        }
                    }
                }
                return false;
            }
            if (function.getParameters() != null) {
                for (Expression item : function.getParameters()) {
                    if (containsUnsupportedAggregate(item, tableContext)) {
                        return true;
                    }
                }
            }
            return false;
        }
        if (expression instanceof BinaryExpression binaryExpression) {
            return containsUnsupportedAggregate(binaryExpression.getLeftExpression(), tableContext)
                    || containsUnsupportedAggregate(binaryExpression.getRightExpression(), tableContext);
        }
        if (expression instanceof ParenthesedExpressionList parenthesis) {
            for (Object item : parenthesis) {
                if (item instanceof Expression child && containsUnsupportedAggregate(child, tableContext)) {
                    return true;
                }
            }
            return false;
        }
        if (expression instanceof NotExpression notExpression) {
            return containsUnsupportedAggregate(notExpression.getExpression(), tableContext);
        }
        if (expression instanceof CaseExpression caseExpression) {
            if (containsUnsupportedAggregate(caseExpression.getSwitchExpression(), tableContext)
                    || containsUnsupportedAggregate(caseExpression.getElseExpression(), tableContext)) {
                return true;
            }
            if (caseExpression.getWhenClauses() != null) {
                for (WhenClause whenClause : caseExpression.getWhenClauses()) {
                    if (containsUnsupportedAggregate(whenClause.getWhenExpression(), tableContext)
                            || containsUnsupportedAggregate(whenClause.getThenExpression(), tableContext)) {
                        return true;
                    }
                }
            }
            return false;
        }
        if (expression instanceof AnalyticExpression analyticExpression) {
            return containsUnsupportedAggregate(analyticExpression.getExpression(), tableContext)
                    || containsUnsupportedAggregate(analyticExpression.getFilterExpression(), tableContext)
                    || containsUnsupportedAggregate(analyticExpression.getOffset(), tableContext)
                    || containsUnsupportedAggregate(analyticExpression.getDefaultValue(), tableContext);
        }
        if (expression instanceof Select select) {
            if (select instanceof PlainSelect plainSelect) {
                return containsUnsupportedAggregate(plainSelect.getHaving(), tableContext)
                        || containsUnsupportedAggregate(plainSelect.getQualify(), tableContext);
            }
            return false;
        }
        return false;
    }

    private boolean isAggregateFunction(Function function) {
        String name = function.getName();
        if (name == null) {
            return false;
        }
        return switch (name.toUpperCase(Locale.ROOT)) {
            case "COUNT", "SUM", "AVG", "MIN", "MAX", "LISTAGG", "STRING_AGG", "GROUP_CONCAT", "ARRAY_AGG" -> true;
            default -> false;
        };
    }

    /**
     * 为派生表生成一份可供外层继续解析的伪表规则。
     *
     * <p>外层查询块只能看到派生表投影列名，看不到内部真实物理列，因此这里要把子查询输出重新映射成
     * 新的 `EncryptTableRule`，这样外层 `t.phone = ?` 之类的条件才有机会继续被改写。</p>
     */
    private EncryptTableRule buildDerivedTableRule(String alias, Select select) {
        if (select instanceof ParenthesedSelect parenthesedSelect && parenthesedSelect.getSelect() != null) {
            return buildDerivedTableRule(alias, parenthesedSelect.getSelect());
        }
        if (select instanceof SetOperationList setOperationList) {
            return setOperationList.getSelects().isEmpty() ? null : buildDerivedTableRule(alias, setOperationList.getSelect(0));
        }
        if (!(select instanceof PlainSelect plainSelect)) {
            return null;
        }
        TableContext childContext = new TableContext();
        registerLookupFromItem(childContext, plainSelect.getFromItem());
        if (plainSelect.getJoins() != null) {
            for (Join join : plainSelect.getJoins()) {
                registerLookupFromItem(childContext, join.getRightItem());
            }
        }
        EncryptTableRule derivedRule = new EncryptTableRule(alias);
        if (plainSelect.getSelectItems() == null) {
            return null;
        }
        for (SelectItem<?> item : plainSelect.getSelectItems()) {
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
            if (aliasName.startsWith("__enc_assisted_") || aliasName.startsWith("__enc_like_")) {
                continue;
            }
            derivedRule.addColumnRule(projectDerivedRule(aliasName, sourceRule));
        }
        return derivedRule.getColumnRules().isEmpty() ? null : derivedRule;
    }

    private void registerLookupFromItem(TableContext tableContext, FromItem fromItem) {
        if (fromItem instanceof Table table) {
            registerTable(tableContext, table);
            return;
        }
        if (fromItem instanceof ParenthesedSelect parenthesedSelect && parenthesedSelect.getAlias() != null
                && parenthesedSelect.getAlias().getName() != null && parenthesedSelect.getSelect() != null) {
            EncryptTableRule derivedRule = buildDerivedTableRule(parenthesedSelect.getAlias().getName(), parenthesedSelect.getSelect());
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
                sourceRule.hasAssistedQueryColumn() ? hiddenAssistedAlias(projectedColumn) : null,
                sourceRule.assistedQueryAlgorithm(),
                sourceRule.hasLikeQueryColumn() ? hiddenLikeAlias(projectedColumn) : null,
                sourceRule.likeQueryAlgorithm(),
                FieldStorageMode.SAME_TABLE,
                null,
                projectedColumn,
                sourceRule.sourceIdProperty(),
                sourceRule.sourceIdColumn(),
                sourceRule.storageIdColumn()
        );
    }

    private void registerTable(TableContext tableContext, Table table) {
        EncryptTableRule rule = metadataRegistry.findByTable(table.getName()).orElse(null);
        if (rule == null) {
            return;
        }
        tableContext.register(table.getName(), table.getAlias() != null ? table.getAlias().getName() : null, rule);
    }

    private Column buildColumn(Column source, String targetColumn) {
        Column column = new Column(quote(targetColumn));
        if (source.getTable() != null && source.getTable().getName() != null) {
            // 这里只保留表引用名（表名或别名），避免生成类似 "t AS t.`col`" 的非法 SQL。
            column.setTable(new Table(source.getTable().getName()));
        }
        return column;
    }

    private String transformCipher(EncryptColumnRule rule, Object plainValue) {
        return applyTransform(rule, plainValue, algorithmRegistry.cipher(rule.cipherAlgorithm()));
    }

    private String transformAssisted(EncryptColumnRule rule, Object plainValue) {
        return applyTransform(rule, plainValue, algorithmRegistry.assisted(rule.assistedQueryAlgorithm()));
    }

    private String transformLike(EncryptColumnRule rule, Object plainValue) {
        return applyTransform(rule, plainValue, algorithmRegistry.like(rule.likeQueryAlgorithm()));
    }

    private String applyTransform(EncryptColumnRule rule, Object plainValue, Object algorithm) {
        if (plainValue == null) {
            return null;
        }
        String value = String.valueOf(plainValue);
        if (algorithm instanceof CipherAlgorithm cipherAlgorithm) {
            return cipherAlgorithm.encrypt(value);
        }
        if (algorithm instanceof AssistedQueryAlgorithm assistedQueryAlgorithm) {
            return assistedQueryAlgorithm.transform(value);
        }
        if (algorithm instanceof LikeQueryAlgorithm likeQueryAlgorithm) {
            return likeQueryAlgorithm.transform(value);
        }
        throw new EncryptionConfigurationException("Unsupported algorithm for field: " + rule.property());
    }

    /**
     * 一次 SQL 改写过程中的可变上下文。
     *
     * <p>它同时维护当前已消费到第几个原始参数、改写后的参数映射列表以及新增附加参数。
     * 排查参数个数不一致、顺序错位或 `BoundSql.additionalParameter` 被覆盖时，应优先检查这里。</p>
     */
    private static final class RewriteContext {

        private final Configuration configuration;
        private final BoundSql boundSql;
        private final List<ParameterMapping> parameterMappings;
        private final Map<String, MaskedValue> maskedParameters = new LinkedHashMap<>();
        private int currentParameterIndex;
        private int generatedIndex;
        private boolean changed;

        private RewriteContext(Configuration configuration, BoundSql boundSql) {
            this.configuration = configuration;
            this.boundSql = boundSql;
            this.parameterMappings = new ArrayList<>(boundSql.getParameterMappings());
        }

        private int consumeOriginal() {
            return currentParameterIndex++;
        }

        private Object originalValue(int index, ParameterValueResolver resolver) {
            if (index < 0 || index >= parameterMappings.size()) {
                return null;
            }
            return resolver.resolve(configuration, boundSql, boundSql.getParameterObject(), parameterMappings.get(index));
        }

        private void replaceLastConsumed(Object value, MaskingMode maskingMode) {
            replaceParameter(currentParameterIndex - 1, value, maskingMode);
        }

        /**
         * 在当前位置插入一个新的合成参数。
         *
         * <p>这主要服务于辅助列和 LIKE 列，因为它们会在原 SQL 的参数序列中新增占位符。
         * 如果不是按当前位置插入，而是简单追加到末尾，JDBC 绑定顺序就会和 SQL 占位符顺序错开。</p>
         */
        private JdbcParameter insertSynthetic(Object value, MaskingMode maskingMode) {
            String property = nextSyntheticName();
            // 辅助列参数必须插入当前位置，才能保证 SQL 占位符顺序始终对齐。
            parameterMappings.add(currentParameterIndex, new ParameterMapping.Builder(configuration, property,
                    value == null ? String.class : value.getClass()).build());
            boundSql.setAdditionalParameter(property, value);
            maskedParameters.put(property, mask(maskingMode, value));
            currentParameterIndex++;
            changed = true;
            return new JdbcParameter();
        }

        /**
         * 用新的合成属性替换原有参数槽位。
         *
         * <p>这里不能复用原属性名，否则 MyBatis 在运行时仍可能回到业务参数对象中取同名值，
         * 导致已经改写完成的附加参数被原始明文覆盖。</p>
         */
        private void replaceParameter(int parameterIndex, Object value, MaskingMode maskingMode) {
            if (parameterIndex < 0 || parameterIndex >= parameterMappings.size()) {
                return;
            }
            ParameterMapping original = parameterMappings.get(parameterIndex);
            String property = nextSyntheticName();
            // 不能复用原始属性名，否则 MyBatis 可能会被业务参数对象中的同名值覆盖。
            ParameterMapping rewritten = new ParameterMapping.Builder(configuration, property,
                    value == null ? String.class : value.getClass())
                    .jdbcType(original.getJdbcType())
                    .build();
            parameterMappings.set(parameterIndex, rewritten);
            boundSql.setAdditionalParameter(property, value);
            maskedParameters.put(property, mask(maskingMode, value));
            changed = true;
        }

        private void removeParameter(int parameterIndex) {
            if (parameterIndex < 0 || parameterIndex >= parameterMappings.size()) {
                return;
            }
            parameterMappings.remove(parameterIndex);
            if (parameterIndex < currentParameterIndex) {
                currentParameterIndex--;
            }
            changed = true;
        }

        private String nextSyntheticName() {
            generatedIndex++;
            return "__encrypt_generated_" + generatedIndex;
        }

        private MaskedValue mask(MaskingMode maskingMode, Object value) {
            if (value == null) {
                return new MaskedValue(maskingMode.name(), "<null>");
            }
            if (maskingMode == MaskingMode.HASH) {
                return new MaskedValue(maskingMode.name(), String.valueOf(value));
            }
            return new MaskedValue(maskingMode.name(), "***");
        }
    }

    /**
     * 当前查询块可见表规则的解析上下文。
     *
     * <p>它把真实表名、别名和派生表别名统一收敛到一份查找表里，负责回答“当前列到底属于哪张表”。
     * 复杂 JOIN、子查询、派生表场景下如果出现字段歧义或规则丢失，通常都和这里的注册结果有关。</p>
     */
    private static final class TableContext {

        private final Map<String, EncryptTableRule> ruleByAlias = new LinkedHashMap<>();

        private void register(String tableName, String alias, EncryptTableRule rule) {
            ruleByAlias.put(NameUtils.normalizeIdentifier(tableName), rule);
            if (alias != null && !alias.isBlank()) {
                ruleByAlias.put(NameUtils.normalizeIdentifier(alias), rule);
            }
        }

        private void registerDerived(String alias, EncryptTableRule rule) {
            if (alias != null && !alias.isBlank()) {
                ruleByAlias.put(NameUtils.normalizeIdentifier(alias), rule);
            }
        }

        private Optional<EncryptColumnRule> resolve(Column column) {
            if (column.getTable() != null && column.getTable().getName() != null && !column.getTable().getName().isBlank()) {
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

        private List<EncryptColumnRule> rulesForSelectExpansion(Table table) {
            if (table != null && table.getName() != null && !table.getName().isBlank()) {
                EncryptTableRule rule = ruleByAlias.get(NameUtils.normalizeIdentifier(table.getName()));
                return rule == null ? List.of() : new ArrayList<>(rule.getColumnRules());
            }
            Collection<EncryptTableRule> uniqueRules = uniqueRules();
            if (uniqueRules.size() != 1) {
                return List.of();
            }
            return new ArrayList<>(uniqueRules.iterator().next().getColumnRules());
        }

        private Optional<EncryptColumnRule> resolveProjected(Column column) {
            if (column.getTable() != null && column.getTable().getName() != null && !column.getTable().getName().isBlank()) {
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

        private boolean isEmpty() {
            return ruleByAlias.isEmpty();
        }
    }

    private record WriteValue(Expression expression, Object plainValue, boolean parameterized) {
    }

    private record ColumnResolution(Column column, EncryptColumnRule rule, boolean leftColumn) {
    }

    private enum MaskingMode {
        MASKED,
        HASH
    }

    private enum ProjectionMode {
        NORMAL,
        COMPARISON,
        DERIVED
    }

    private String quote(String identifier) {
        SqlDialect dialect = properties.getSqlDialect();
        return dialect == null ? identifier : dialect.quote(identifier);
    }
}
