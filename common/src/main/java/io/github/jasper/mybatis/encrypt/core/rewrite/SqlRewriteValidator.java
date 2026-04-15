package io.github.jasper.mybatis.encrypt.core.rewrite;

import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import io.github.jasper.mybatis.encrypt.exception.EncryptionErrorCode;
import io.github.jasper.mybatis.encrypt.exception.UnsupportedEncryptedOperationException;
import net.sf.jsqlparser.expression.AnalyticExpression;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.CaseExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.NotExpression;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.WhenClause;
import net.sf.jsqlparser.expression.WindowDefinition;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.Distinct;
import net.sf.jsqlparser.statement.select.GroupByElement;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;

import java.util.List;
import java.util.Locale;

/**
 * `SELECT` 改写阶段的能力校验器。
 *
 * <p>把 DISTINCT、聚合、窗口、GROUP BY、ORDER BY 等限制性校验
 * 从 {@link SqlRewriteEngine} 中拆出，避免改写逻辑与限制性规则判断混在一个类里。</p>
 */
final class SqlRewriteValidator {

    void validateSelect(PlainSelect plainSelect, SqlTableContext tableContext) {
        validateDistinct(plainSelect.getDistinct(), plainSelect.getSelectItems(), tableContext);
        validateAggregateExpressions(plainSelect.getSelectItems(), plainSelect.getHaving(), plainSelect.getQualify(), tableContext);
        validateAnalyticExpressions(plainSelect.getSelectItems(), tableContext);
        validateWindowDefinitions(plainSelect.getWindowDefinitions(), tableContext);
        validateGroupBy(plainSelect.getGroupBy(), tableContext);
        validateOrderBy(plainSelect.getOrderByElements(), tableContext);
    }

    private void validateDistinct(Distinct distinct, List<SelectItem<?>> selectItems, SqlTableContext tableContext) {
        if (distinct == null || selectItems == null) {
            return;
        }
        for (SelectItem<?> item : selectItems) {
            if (containsEncryptedReference(item.getExpression(), tableContext)) {
                throw new UnsupportedEncryptedOperationException(EncryptionErrorCode.UNSUPPORTED_ENCRYPTED_DISTINCT,
                        "DISTINCT is not supported on encrypted fields.");
            }
        }
    }

    private void validateAggregateExpressions(List<SelectItem<?>> selectItems,
                                              Expression having,
                                              Expression qualify,
                                              SqlTableContext tableContext) {
        if (selectItems != null) {
            for (SelectItem<?> item : selectItems) {
                if (containsUnsupportedAggregate(item.getExpression(), tableContext)) {
                    throw new UnsupportedEncryptedOperationException(EncryptionErrorCode.UNSUPPORTED_ENCRYPTED_AGGREGATION,
                            "Aggregate function is not supported on encrypted fields.");
                }
            }
        }
        if (containsUnsupportedAggregate(having, tableContext)
                || containsUnsupportedAggregate(qualify, tableContext)) {
            throw new UnsupportedEncryptedOperationException(EncryptionErrorCode.UNSUPPORTED_ENCRYPTED_AGGREGATION,
                    "Aggregate function is not supported on encrypted fields.");
        }
    }

    private void validateAnalyticExpressions(List<SelectItem<?>> selectItems, SqlTableContext tableContext) {
        if (selectItems == null) {
            return;
        }
        for (SelectItem<?> item : selectItems) {
            Expression expression = item.getExpression();
            if (expression instanceof AnalyticExpression && containsEncryptedReference(expression, tableContext)) {
                throw new UnsupportedEncryptedOperationException(EncryptionErrorCode.UNSUPPORTED_ENCRYPTED_WINDOW,
                        "Window function is not supported on encrypted fields.");
            }
        }
    }

    private void validateWindowDefinitions(List<WindowDefinition> windowDefinitions, SqlTableContext tableContext) {
        if (windowDefinitions == null) {
            return;
        }
        for (WindowDefinition windowDefinition : windowDefinitions) {
            if (containsEncryptedReference(windowDefinition, tableContext)) {
                throw new UnsupportedEncryptedOperationException(EncryptionErrorCode.UNSUPPORTED_ENCRYPTED_WINDOW,
                        "Named window definition is not supported on encrypted fields.");
            }
        }
    }

    private void validateGroupBy(GroupByElement groupByElement, SqlTableContext tableContext) {
        if (groupByElement == null || groupByElement.getGroupByExpressionList() == null) {
            return;
        }
        for (Object item : groupByElement.getGroupByExpressionList()) {
            if (item instanceof Expression && containsEncryptedReference((Expression) item, tableContext)) {
                throw new UnsupportedEncryptedOperationException(EncryptionErrorCode.UNSUPPORTED_ENCRYPTED_GROUP_BY,
                        "GROUP BY is not supported on encrypted fields.");
            }
        }
    }

    private void validateOrderBy(List<OrderByElement> orderByElements, SqlTableContext tableContext) {
        if (orderByElements == null) {
            return;
        }
        for (OrderByElement element : orderByElements) {
            EncryptColumnRule rule = resolveEncryptedRule(element.getExpression(), tableContext);
            if (rule != null) {
                throw new UnsupportedEncryptedOperationException(EncryptionErrorCode.UNSUPPORTED_ENCRYPTED_ORDER_BY,
                        "ORDER BY is not supported on encrypted field: " + rule.property());
            }
        }
    }

    private EncryptColumnRule resolveEncryptedRule(Expression expression, SqlTableContext tableContext) {
        if (!(expression instanceof Column)) {
            return null;
        }
        return tableContext.resolve((Column) expression).orElse(null);
    }

    private boolean containsEncryptedReference(Expression expression, SqlTableContext tableContext) {
        if (expression == null) {
            return false;
        }
        if (resolveEncryptedRule(expression, tableContext) != null) {
            return true;
        }
        if (expression instanceof Parenthesis) {
            return containsEncryptedReference(((Parenthesis) expression).getExpression(), tableContext);
        }
        if (expression instanceof ParenthesedExpressionList) {
            ParenthesedExpressionList<?> parenthesed = (ParenthesedExpressionList<?>) expression;
            for (Expression item : parenthesed) {
                if (containsEncryptedReference(item, tableContext)) {
                    return true;
                }
            }
            return false;
        }
        if (expression instanceof BinaryExpression) {
            BinaryExpression binaryExpression = (BinaryExpression) expression;
            return containsEncryptedReference(binaryExpression.getLeftExpression(), tableContext)
                    || containsEncryptedReference(binaryExpression.getRightExpression(), tableContext);
        }
        if (expression instanceof Function && ((Function) expression).getParameters() != null) {
            for (Expression item : ((Function) expression).getParameters()) {
                if (containsEncryptedReference(item, tableContext)) {
                    return true;
                }
            }
            return false;
        }
        if (expression instanceof CaseExpression) {
            CaseExpression caseExpression = (CaseExpression) expression;
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
        if (expression instanceof NotExpression) {
            return containsEncryptedReference(((NotExpression) expression).getExpression(), tableContext);
        }
        if (expression instanceof AnalyticExpression) {
            AnalyticExpression analyticExpression = (AnalyticExpression) expression;
            if (containsEncryptedReference(analyticExpression.getExpression(), tableContext)
                    || containsEncryptedReference(analyticExpression.getFilterExpression(), tableContext)
                    || containsEncryptedReference(analyticExpression.getOffset(), tableContext)
                    || containsEncryptedReference(analyticExpression.getDefaultValue(), tableContext)) {
                return true;
            }
            if (analyticExpression.getPartitionExpressionList() != null) {
                for (Object item : analyticExpression.getPartitionExpressionList()) {
                    if (item instanceof Expression && containsEncryptedReference((Expression) item, tableContext)) {
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
            return analyticExpression.getWindowDefinition() != null
                    && containsEncryptedReference(analyticExpression.getWindowDefinition(), tableContext);
        }
        return false;
    }

    private boolean containsEncryptedReference(WindowDefinition windowDefinition, SqlTableContext tableContext) {
        if (windowDefinition == null) {
            return false;
        }
        if (windowDefinition.getPartitionExpressionList() != null) {
            for (Object item : windowDefinition.getPartitionExpressionList()) {
                if (item instanceof Expression && containsEncryptedReference((Expression) item, tableContext)) {
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

    private boolean containsUnsupportedAggregate(Expression expression, SqlTableContext tableContext) {
        if (expression == null) {
            return false;
        }
        if (expression instanceof Function) {
            Function function = (Function) expression;
            if (isAggregateFunction(function)) {
                if (function.isAllColumns()) {
                    return false;
                }
                if (function.getParameters() != null) {
                    for (Expression item : function.getParameters()) {
                        if (containsEncryptedReference(item, tableContext)
                                || containsUnsupportedAggregate(item, tableContext)) {
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
        if (expression instanceof BinaryExpression) {
            BinaryExpression binaryExpression = (BinaryExpression) expression;
            return containsUnsupportedAggregate(binaryExpression.getLeftExpression(), tableContext)
                    || containsUnsupportedAggregate(binaryExpression.getRightExpression(), tableContext);
        }
        if (expression instanceof Parenthesis) {
            return containsUnsupportedAggregate(((Parenthesis) expression).getExpression(), tableContext);
        }
        if (expression instanceof ParenthesedExpressionList) {
            ParenthesedExpressionList<?> parenthesed = (ParenthesedExpressionList<?>) expression;
            for (Expression item : parenthesed) {
                if (containsUnsupportedAggregate(item, tableContext)) {
                    return true;
                }
            }
            return false;
        }
        if (expression instanceof NotExpression) {
            return containsUnsupportedAggregate(((NotExpression) expression).getExpression(), tableContext);
        }
        if (expression instanceof CaseExpression) {
            CaseExpression caseExpression = (CaseExpression) expression;
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
        if (expression instanceof AnalyticExpression) {
            AnalyticExpression analyticExpression = (AnalyticExpression) expression;
            return containsUnsupportedAggregate(analyticExpression.getExpression(), tableContext)
                    || containsUnsupportedAggregate(analyticExpression.getFilterExpression(), tableContext)
                    || containsUnsupportedAggregate(analyticExpression.getOffset(), tableContext)
                    || containsUnsupportedAggregate(analyticExpression.getDefaultValue(), tableContext);
        }
        if (expression instanceof Select) {
            Select select = (Select) expression;
            if (select instanceof PlainSelect) {
                PlainSelect plainSelect = (PlainSelect) select;
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
        String upperName = name.toUpperCase(Locale.ROOT);
        return "COUNT".equals(upperName)
                || "SUM".equals(upperName)
                || "AVG".equals(upperName)
                || "MIN".equals(upperName)
                || "MAX".equals(upperName)
                || "LISTAGG".equals(upperName)
                || "STRING_AGG".equals(upperName)
                || "GROUP_CONCAT".equals(upperName)
                || "ARRAY_AGG".equals(upperName);
    }
}
