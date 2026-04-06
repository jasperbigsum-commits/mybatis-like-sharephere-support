package tech.jasper.mybatis.encrypt.core.rewrite;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.GreaterThan;
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.LikeExpression;
import net.sf.jsqlparser.expression.operators.relational.MinorThan;
import net.sf.jsqlparser.expression.operators.relational.MinorThanEquals;
import net.sf.jsqlparser.expression.operators.relational.NotEqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SelectItemVisitorAdapter;
import net.sf.jsqlparser.statement.select.Values;
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
import tech.jasper.mybatis.encrypt.exception.EncryptionConfigurationException;
import tech.jasper.mybatis.encrypt.exception.UnsupportedEncryptedOperationException;
import tech.jasper.mybatis.encrypt.util.NameUtils;

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
        if (values == null || !(values.getExpressions() instanceof ExpressionList)) {
            throw new UnsupportedEncryptedOperationException("Only VALUES inserts are supported for encrypted tables.");
        }
        ExpressionList expressionList = (ExpressionList) values.getExpressions();
        List<Column> originalColumns = new ArrayList<>(insert.getColumns());
        List<Expression> originalExpressions = new ArrayList<>(expressionList.getExpressions());
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
                consumeExpression(expression, context);
                context.changed = true;
                continue;
            }
            rewrittenColumns.add(column);
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
            List<Expression> originalExpressions = new ArrayList<>(updateValues.getExpressions());
            List<Column> rewrittenColumns = new ArrayList<>();
            List<Expression> rewrittenExpressions = new ArrayList<>();
            for (int index = 0; index < originalColumns.size(); index++) {
                Column column = originalColumns.get(index);
                Expression expression = originalExpressions.get(index);
                EncryptColumnRule rule = tableContext.resolve(column).orElse(null);
                if (rule == null) {
                    rewrittenColumns.add(column);
                    rewrittenExpressions.add(passthroughWriteExpression(expression, context));
                    continue;
                }
                if (rule.isStoredInSeparateTable()) {
                    consumeExpression(expression, context);
                    context.changed = true;
                    continue;
                }
                rewrittenColumns.add(column);
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
            updateValues.getExpressions().clear();
            updateValues.getExpressions().addAll(rewrittenExpressions);
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
        if (!(select.getSelectBody() instanceof PlainSelect plainSelect)) {
            throw new UnsupportedEncryptedOperationException("Only plain select is supported for encrypted SQL rewrite.");
        }
        TableContext tableContext = new TableContext();
        registerFromItem(tableContext, plainSelect.getFromItem());
        if (plainSelect.getJoins() != null) {
            for (Join join : plainSelect.getJoins()) {
                registerFromItem(tableContext, join.getRightItem());
            }
        }
        if (tableContext.isEmpty()) {
            return;
        }
        plainSelect.setWhere(rewriteCondition(plainSelect.getWhere(), tableContext, context));
        stripSeparateTableSelectItems(plainSelect, tableContext);
        // ORDER BY on encrypted fields is rejected because helper columns cannot preserve sort semantics safely.
    }

    private Expression rewriteCondition(Expression expression, TableContext tableContext, RewriteContext context) {
        if (expression == null) {
            return null;
        }
        if (expression instanceof Parenthesis parenthesis) {
            parenthesis.setExpression(rewriteCondition(parenthesis.getExpression(), tableContext, context));
            return parenthesis;
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
            for (Object item : function.getParameters().getExpressions()) {
                rewriteCondition((Expression) item, tableContext, context);
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
        String targetColumn = rule.hasAssistedQueryColumn() ? rule.assistedQueryColumn() : rule.column();
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
            consumeItemsList(expression.getRightExpression(), context);
            return expression;
        }
        EncryptColumnRule rule = resolution.rule();
        if (rule.isStoredInSeparateTable()) {
            throw new UnsupportedEncryptedOperationException("IN query is not supported for separate-table encrypted field: "
                    + rule.property());
        }
        // IN query uses the same target-column selection strategy as equality queries.
        String targetColumn = rule.hasAssistedQueryColumn() ? rule.assistedQueryColumn() : rule.column();
        if (!(expression.getRightExpression() instanceof ExpressionList expressionList)) {
            throw new UnsupportedEncryptedOperationException("Sub query IN is not supported on encrypted fields.");
        }
        for (Object item : expressionList.getExpressions()) {
            Expression current = (Expression) item;
            rewriteOperand(current, context,
                    rule.hasAssistedQueryColumn() ? transformAssisted(rule, readOperandValue(current, context))
                            : transformCipher(rule, readOperandValue(current, context)),
                    rule.hasAssistedQueryColumn() ? MaskingMode.HASH : MaskingMode.MASKED);
        }
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
        if (expression instanceof NullValue) {
            return null;
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
        if (expression instanceof Parenthesis parenthesis) {
            consumeExpression(parenthesis.getExpression(), context);
            return;
        }
        if (expression instanceof BinaryExpression binaryExpression) {
            consumeExpression(binaryExpression.getLeftExpression(), context);
            consumeExpression(binaryExpression.getRightExpression(), context);
            return;
        }
        if (expression instanceof Function function && function.getParameters() != null) {
            for (Object item : function.getParameters().getExpressions()) {
                consumeExpression((Expression) item, context);
            }
        }
    }

    private void consumeItemsList(Object itemsList, RewriteContext context) {
        if (itemsList instanceof ExpressionList expressionList) {
            for (Object item : expressionList.getExpressions()) {
                consumeExpression((Expression) item, context);
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

    private void stripSeparateTableSelectItems(PlainSelect plainSelect, TableContext tableContext) {
        if (plainSelect.getSelectItems() == null) {
            return;
        }
        List<SelectItem<?>> retained = new ArrayList<>();
        for (SelectItem<?> item : plainSelect.getSelectItems()) {
            boolean removable = isSeparateTableSelectItem(item, tableContext);
            if (!removable) {
                retained.add(item);
            }
        }
        if (!retained.isEmpty()) {
            plainSelect.setSelectItems(retained);
        }
    }

    private boolean isSeparateTableSelectItem(SelectItem<?> item, TableContext tableContext) {
        final boolean[] removable = {false};
        item.accept(new SelectItemVisitorAdapter() {
            @Override
            public void visit(SelectItem selectExpressionItem) {
                ColumnResolution resolution = resolveEncryptedColumn(selectExpressionItem.getExpression(), tableContext);
                removable[0] = resolution != null && resolution.rule().isStoredInSeparateTable();
            }
        });
        return removable[0];
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
        existsExpression.setRightExpression(subQueryBody);
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

    private void registerFromItem(TableContext tableContext, FromItem fromItem) {
        if (fromItem instanceof Table table) {
            registerTable(tableContext, table);
        }
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
            Table table = new Table(source.getTable().getName());
            if (source.getTable().getAlias() != null) {
                table.setAlias(new Alias(source.getTable().getAlias().getName(), false));
            }
            column.setTable(table);
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

        private Optional<EncryptColumnRule> resolve(Column column) {
            if (column.getTable() != null && column.getTable().getName() != null && !column.getTable().getName().isBlank()) {
                EncryptTableRule tableRule = ruleByAlias.get(NameUtils.normalizeIdentifier(column.getTable().getName()));
                if (tableRule != null) {
                    return tableRule.findByColumn(column.getColumnName());
                }
            }
            EncryptColumnRule candidate = null;
            for (EncryptTableRule tableRule : ruleByAlias.values()) {
                EncryptColumnRule rule = tableRule.findByColumn(column.getColumnName()).orElse(null);
                if (rule == null) {
                    continue;
                }
                if (candidate != null) {
                    // Unqualified encrypted columns are rejected when multiple encrypted tables match the same name.
                    throw new UnsupportedEncryptedOperationException(
                            "Ambiguous encrypted column reference: " + column.getFullyQualifiedName());
                }
                candidate = rule;
            }
            return Optional.ofNullable(candidate);
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

    private String quote(String identifier) {
        SqlDialect dialect = properties.getSqlDialect();
        return dialect == null ? identifier : dialect.quote(identifier);
    }
}
