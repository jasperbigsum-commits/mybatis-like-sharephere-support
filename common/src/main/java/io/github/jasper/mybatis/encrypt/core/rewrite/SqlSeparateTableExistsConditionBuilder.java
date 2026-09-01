package io.github.jasper.mybatis.encrypt.core.rewrite;

import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.expression.operators.relational.LikeExpression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.SelectItem;

import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 独立表 EXISTS 条件构建器。
 */
final class SqlSeparateTableExistsConditionBuilder {

    private final BiFunction<Column, String, Column> columnBuilder;
    private final Function<String, String> identifierQuoter;

    SqlSeparateTableExistsConditionBuilder(BiFunction<Column, String, Column> columnBuilder,
                                           Function<String, String> identifierQuoter) {
        this.columnBuilder = columnBuilder;
        this.identifierQuoter = identifierQuoter;
    }

    Expression buildExistsCondition(Column sourceColumn, EncryptColumnRule rule, Expression valuePredicate) {
        PlainSelect subQueryBody = new PlainSelect();
        subQueryBody.addSelectItems(SelectItem.from(new LongValue(1)));
        subQueryBody.setFromItem(new Table(identifierQuoter.apply(rule.storageTable())));
        subQueryBody.setWhere(new AndExpression(buildJoinPredicate(sourceColumn, rule), valuePredicate));
        ExistsExpression existsExpression = new ExistsExpression();
        existsExpression.setRightExpression(new ParenthesedSelect().withSelect(subQueryBody));
        return existsExpression;
    }

    Expression buildPresenceCondition(Column sourceColumn, EncryptColumnRule rule, boolean shouldExist) {
        PlainSelect subQueryBody = new PlainSelect();
        subQueryBody.addSelectItems(SelectItem.from(new LongValue(1)));
        subQueryBody.setFromItem(new Table(identifierQuoter.apply(rule.storageTable())));
        subQueryBody.setWhere(buildJoinPredicate(sourceColumn, rule));
        ExistsExpression existsExpression = new ExistsExpression();
        existsExpression.setRightExpression(new ParenthesedSelect().withSelect(subQueryBody));
        existsExpression.setNot(!shouldExist);
        return existsExpression;
    }

    Expression buildEqualityPredicate(String targetColumn, Expression valueExpression) {
        return buildEqualityPredicate(new Column(identifierQuoter.apply(targetColumn)), valueExpression);
    }

    Expression buildEqualityPredicate(Column targetColumn, Expression valueExpression) {
        EqualsTo equalsTo = new EqualsTo();
        equalsTo.setLeftExpression(targetColumn);
        equalsTo.setRightExpression(valueExpression);
        return equalsTo;
    }

    Expression buildLikePredicate(String targetColumn, Expression valueExpression) {
        LikeExpression likeExpression = new LikeExpression();
        likeExpression.setLeftExpression(new Column(identifierQuoter.apply(targetColumn)));
        likeExpression.setRightExpression(valueExpression);
        return likeExpression;
    }

    Expression wrapWithParenthesizedOr(Expression leftExpression, Expression rightExpression) {
        OrExpression orExpression = new OrExpression(leftExpression, rightExpression);
        Parenthesis parenthesis = new Parenthesis();
        parenthesis.setExpression(orExpression);
        return parenthesis;
    }

    private Expression buildJoinPredicate(Column sourceColumn, EncryptColumnRule rule) {
        EqualsTo joinEquals = new EqualsTo();
        joinEquals.setLeftExpression(new Column(identifierQuoter.apply(rule.assistedQueryColumn())));
        joinEquals.setRightExpression(columnBuilder.apply(sourceColumn, rule.column()));
        return joinEquals;
    }
}
