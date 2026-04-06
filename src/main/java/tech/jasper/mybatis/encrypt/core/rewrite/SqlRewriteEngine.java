package tech.jasper.mybatis.encrypt.core.rewrite;

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
import tech.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import tech.jasper.mybatis.encrypt.algorithm.AssistedQueryAlgorithm;
import tech.jasper.mybatis.encrypt.algorithm.CipherAlgorithm;
import tech.jasper.mybatis.encrypt.algorithm.LikeQueryAlgorithm;
import tech.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import tech.jasper.mybatis.encrypt.config.SqlDialect;
import tech.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import tech.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import tech.jasper.mybatis.encrypt.core.metadata.EncryptTableRule;
import tech.jasper.mybatis.encrypt.core.metadata.FieldStorageMode;
import tech.jasper.mybatis.encrypt.exception.EncryptionConfigurationException;
import tech.jasper.mybatis.encrypt.exception.UnsupportedEncryptedOperationException;
import tech.jasper.mybatis.encrypt.util.NameUtils;

import java.util.*;

/**
 * SQL rewrite engine.
 *
 * <p>Parses MyBatis SQL and rewrites encrypted fields, assisted query fields,
 * and LIKE helper fields before execution. The implementation fails fast for
 * unsupported range, sort, and ambiguous query scenarios.</p>
 */
public class SqlRewriteEngine {

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
     * Rewrite one MyBatis execution request.
     *
     * @param mappedStatement current mapped statement
     * @param boundSql original SQL and parameter context
     * @return rewrite result; unchanged when no encryption rule matches
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
            return new RewriteResult(true, rewrittenSql, context.parameterMappings, context.maskedParameters,
                    sqlLogMasker.mask(rewrittenSql, context.maskedParameters));
        } catch (UnsupportedEncryptedOperationException ex) {
            throw ex;
        } catch (Exception ex) {
            if (properties.isFailOnMissingRule()) {
                throw new EncryptionConfigurationException("Failed to rewrite encrypted SQL: " + boundSql.getSql(), ex);
            }
            return RewriteResult.unchanged();
        }
    }

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
                discardExpression(expression, context);
                context.changed = true;
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
                    discardExpression(expression, context);
                    context.changed = true;
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
        // Only the WHERE clause is redirected to query columns; the SET clause still writes ciphertext to the main column.
        update.setWhere(rewriteCondition(update.getWhere(), tableContext, context));
    }

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
            rewriteSelectItems(plainSelect, tableContext, projectionMode);
        }
        validateAnalyticExpressions(plainSelect.getSelectItems(), tableContext);
        validateWindowDefinitions(plainSelect.getWindowDefinitions(), tableContext);
        validateGroupBy(plainSelect.getGroupBy(), tableContext);
        validateOrderBy(plainSelect.getOrderByElements(), tableContext);
    }

    // Parenthesis 鍦?JSqlParser 5.x 涓凡搴熷純浣嗕粛鐢ㄤ簬琛ㄧず鏉′欢鎷彿
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
        // IN query uses the same target-column selection strategy as equality queries.
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

    private void rewriteSelectItems(PlainSelect plainSelect, TableContext tableContext, ProjectionMode projectionMode) {
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
                continue;
            }
            if (expression instanceof AllColumns) {
                rewritten.add(item);
                appendSelectAliasesForWildcard(rewritten, tableContext.rulesForSelectExpansion(null), null, projectionMode);
                continue;
            }
            ColumnResolution resolution = resolveEncryptedColumn(expression, tableContext);
            if (resolution == null) {
                rewritten.add(item);
                continue;
            }
            if (resolution.rule().isStoredInSeparateTable()) {
                continue;
            }
            rewritten.add(buildSelectStorageItem(item, resolution));
            if (projectionMode == ProjectionMode.DERIVED) {
                appendDerivedHelperSelectItems(rewritten, item, resolution);
            }
        }
        if (!rewritten.isEmpty()) {
            plainSelect.setSelectItems(rewritten);
        }
    }

    private void discardExpression(Expression expression, RewriteContext context) {
        if (expression == null) {
            return;
        }
        if (expression instanceof JdbcParameter) {
            int parameterIndex = context.consumeOriginal();
            context.removeParameter(parameterIndex);
            return;
        }
        if (expression instanceof ParenthesedExpressionList parenthesis) {
            parenthesis.forEach(o -> {
                if (o instanceof Expression exp) {
                    discardExpression(exp, context);
                }
            });
            return;
        }
        if (expression instanceof BinaryExpression binaryExpression) {
            discardExpression(binaryExpression.getLeftExpression(), context);
            discardExpression(binaryExpression.getRightExpression(), context);
            return;
        }
        if (expression instanceof Function function && function.getParameters() != null) {
            for (Expression item : function.getParameters()) {
                discardExpression(item, context);
            }
        }
    }

    private void rewriteComparisonSelectItems(PlainSelect plainSelect, TableContext tableContext) {
        if (plainSelect.getSelectItems() == null) {
            return;
        }
        List<SelectItem<?>> rewritten = new ArrayList<>();
        for (SelectItem<?> item : plainSelect.getSelectItems()) {
            Expression expression = item.getExpression();
            if (expression instanceof AllColumns || expression instanceof AllTableColumns) {
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
        joinEquals.setRightExpression(buildColumn(sourceColumn, rule.sourceIdColumn()));
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

    private Expression buildExistsPresenceSubQuery(Column sourceColumn,
                                                   EncryptColumnRule rule,
                                                   boolean shouldExist) {
        PlainSelect subQueryBody = new PlainSelect();
        subQueryBody.addSelectItems(SelectItem.from(new LongValue(1)));
        subQueryBody.setFromItem(new Table(quote(rule.storageTable())));
        EqualsTo joinEquals = new EqualsTo();
        joinEquals.setLeftExpression(new Column(quote(rule.storageIdColumn())));
        joinEquals.setRightExpression(buildColumn(sourceColumn, rule.sourceIdColumn()));
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
            // 鍙繚鐣欒〃寮曠敤鍚嶏紙鍙兘鏄〃鍚嶆垨鍒悕锛夛紝涓嶅鍒?alias 浠ラ伩鍏嶇敓鎴?"t AS t.`col`" 杩欐牱鐨勯敊璇?SQL
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

        private JdbcParameter insertSynthetic(Object value, MaskingMode maskingMode) {
            String property = nextSyntheticName();
            // Shadow-column parameters must be inserted at the current position so that SQL placeholders stay aligned.
            parameterMappings.add(currentParameterIndex, new ParameterMapping.Builder(configuration, property,
                    value == null ? String.class : value.getClass()).build());
            boundSql.setAdditionalParameter(property, value);
            maskedParameters.put(property, mask(maskingMode, value));
            currentParameterIndex++;
            changed = true;
            return new JdbcParameter();
        }

        private void replaceParameter(int parameterIndex, Object value, MaskingMode maskingMode) {
            if (parameterIndex < 0 || parameterIndex >= parameterMappings.size()) {
                return;
            }
            ParameterMapping original = parameterMappings.get(parameterIndex);
            String property = nextSyntheticName();
            // Do not reuse the original property name, or MyBatis may overwrite values from the business parameter object.
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
