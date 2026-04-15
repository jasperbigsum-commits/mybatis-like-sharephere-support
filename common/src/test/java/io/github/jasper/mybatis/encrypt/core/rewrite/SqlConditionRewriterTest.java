package io.github.jasper.mybatis.encrypt.core.rewrite;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.algorithm.support.NormalizedLikeQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.support.Sm3AssistedQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptTableRule;
import io.github.jasper.mybatis.encrypt.core.metadata.FieldStorageMode;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlConditionRewriterTest {

    @Test
    void shouldRewriteEqualityToAssistedColumnAndReplaceParameter() throws Exception {
        SqlConditionRewriter rewriter = newRewriter(new ArrayList<ProjectionMode>());
        SqlTableContext tableContext = tableContext(sameTableRule());
        SqlRewriteContext context = rewriteContext("SELECT id FROM user_account WHERE phone = ?",
                Collections.singletonList(new ParameterMapping.Builder(new Configuration(), "phone", String.class).build()),
                Collections.<String, Object>singletonMap("phone", "13800138000"));

        Expression rewritten = rewriter.rewrite(parseWhere("SELECT id FROM user_account WHERE phone = ?"), tableContext, context);

        assertTrue(rewritten.toString().contains("`phone_hash` = ?"));
        assertTrue(context.parameterMappings().get(0).getProperty().startsWith("__encrypt_generated_"));
        assertEquals(
                new Sm3AssistedQueryAlgorithm().transform("13800138000"),
                context.maskedParameters().values().iterator().next().value()
        );
    }

    @Test
    void shouldRewriteLikeConcatToLikeAndAssistedFallback() throws Exception {
        SqlConditionRewriter rewriter = newRewriter(new ArrayList<ProjectionMode>());
        SqlTableContext tableContext = tableContext(sameTableRule());
        SqlRewriteContext context = rewriteContext("SELECT id FROM user_account WHERE phone LIKE CONCAT('%', ?, '%')",
                Collections.singletonList(new ParameterMapping.Builder(new Configuration(), "segment", String.class).build()),
                Collections.<String, Object>singletonMap("segment", "AbC"));

        Expression rewritten = rewriter.rewrite(parseWhere("SELECT id FROM user_account WHERE phone LIKE CONCAT('%', ?, '%')"),
                tableContext, context);

        assertTrue(rewritten.toString().contains("`phone_like` LIKE ?"));
        assertTrue(rewritten.toString().contains("`phone_hash` = ?"));
        assertTrue(rewritten.toString().contains(" OR "));
        assertEquals(2, context.parameterMappings().size());
        assertTrue(context.parameterMappings().get(0).getProperty().startsWith("__encrypt_generated_"));
        assertTrue(context.parameterMappings().get(1).getProperty().startsWith("__encrypt_generated_"));
        assertEquals("%abc%", context.originalValue(0));
        assertEquals(
                new Sm3AssistedQueryAlgorithm().transform("AbC"),
                context.originalValue(1)
        );
    }

    @Test
    void shouldRewriteExactLikeToLikeAndAssistedFallback() throws Exception {
        SqlConditionRewriter rewriter = newRewriter(new ArrayList<ProjectionMode>());
        SqlTableContext tableContext = tableContext(sameTableRule());
        SqlRewriteContext context = rewriteContext("SELECT id FROM user_account WHERE phone LIKE ?",
                Collections.singletonList(new ParameterMapping.Builder(new Configuration(), "phone", String.class).build()),
                Collections.<String, Object>singletonMap("phone", "13800138000"));

        Expression rewritten = rewriter.rewrite(parseWhere("SELECT id FROM user_account WHERE phone LIKE ?"),
                tableContext, context);

        assertTrue(rewritten.toString().contains("`phone_hash` = ?"));
        assertTrue(rewritten.toString().contains("`phone_like` LIKE ?"));
        assertTrue(rewritten.toString().contains(" OR "));
        assertEquals(
                new Sm3AssistedQueryAlgorithm().transform("13800138000"),
                context.originalValue(1)
        );
    }

    @Test
    void shouldRewriteSeparateTableLikeToExistsWithAssistedFallback() throws Exception {
        SqlConditionRewriter rewriter = newRewriter(new ArrayList<ProjectionMode>());
        SqlTableContext tableContext = tableContext(separateTableRule());
        SqlRewriteContext context = rewriteContext("SELECT id FROM user_account WHERE phone LIKE CONCAT('%', ?, '%')",
                Collections.singletonList(new ParameterMapping.Builder(new Configuration(), "segment", String.class).build()),
                Collections.<String, Object>singletonMap("segment", "AbC"));

        Expression rewritten = rewriter.rewrite(parseWhere("SELECT id FROM user_account WHERE phone LIKE CONCAT('%', ?, '%')"),
                tableContext, context);

        assertTrue(rewritten.toString().contains("EXISTS"));
        assertTrue(rewritten.toString().contains("`phone_like` LIKE ?"));
        assertTrue(rewritten.toString().contains("`phone_hash` = ?"));
        assertTrue(rewritten.toString().contains(" OR "));
        assertEquals(2, context.parameterMappings().size());
        assertEquals("%abc%", context.originalValue(0));
        assertEquals(new Sm3AssistedQueryAlgorithm().transform("AbC"), context.originalValue(1));
    }

    @Test
    void shouldDispatchComparisonProjectionModeForEncryptedInSubquery() throws Exception {
        List<ProjectionMode> dispatchedModes = new ArrayList<ProjectionMode>();
        SqlConditionRewriter rewriter = newRewriter(dispatchedModes);
        SqlTableContext tableContext = tableContext(sameTableRule());
        SqlRewriteContext context = rewriteContext("SELECT id FROM user_account WHERE phone IN (SELECT phone FROM user_account)",
                Collections.<ParameterMapping>emptyList(),
                Collections.emptyMap());

        Expression rewritten = rewriter.rewrite(
                parseWhere("SELECT id FROM user_account WHERE phone IN (SELECT phone FROM user_account)"),
                tableContext,
                context
        );

        assertTrue(rewritten.toString().contains("`phone_hash` IN"));
        assertEquals(Collections.singletonList(ProjectionMode.COMPARISON), dispatchedModes);
    }

    @Test
    void shouldRewriteSeparateTableEqualityToExistsSubquery() throws Exception {
        SqlConditionRewriter rewriter = newRewriter(new ArrayList<ProjectionMode>());
        SqlTableContext tableContext = tableContext(separateTableRule());
        SqlRewriteContext context = rewriteContext("SELECT id FROM user_account WHERE phone = ?",
                Collections.singletonList(new ParameterMapping.Builder(new Configuration(), "phone", String.class).build()),
                Collections.<String, Object>singletonMap("phone", "13800138000"));

        Expression rewritten = rewriter.rewrite(parseWhere("SELECT id FROM user_account WHERE phone = ?"), tableContext, context);

        assertTrue(rewritten.toString().contains("EXISTS"));
        assertTrue(rewritten.toString().contains("`user_phone_encrypt`"));
        assertTrue(rewritten.toString().contains("`phone_hash` = ?"));
    }

    private SqlConditionRewriter newRewriter(List<ProjectionMode> dispatchedModes) {
        EncryptionValueTransformer transformer = new EncryptionValueTransformer(new AlgorithmRegistry(
                Collections.emptyMap(),
                Collections.singletonMap("sm3", new Sm3AssistedQueryAlgorithm()),
                Collections.singletonMap("like", new NormalizedLikeQueryAlgorithm())
        ));
        return new SqlConditionRewriter(
                transformer,
                this::buildColumn,
                (rule, scenario) -> rule.assistedQueryColumn(),
                (rule, scenario) -> rule.likeQueryColumn(),
                this::quote,
                (select, context, projectionMode) -> dispatchedModes.add(projectionMode)
        );
    }

    private SqlRewriteContext rewriteContext(String sql, List<ParameterMapping> parameterMappings, Object parameterObject) {
        Configuration configuration = new Configuration();
        BoundSql boundSql = new BoundSql(configuration, sql, parameterMappings, parameterObject);
        return new SqlRewriteContext(configuration, boundSql, new ParameterValueResolver());
    }

    private Expression parseWhere(String sql) throws Exception {
        Statement statement = CCJSqlParserUtil.parse(sql);
        Select select = (Select) statement;
        PlainSelect plainSelect = (PlainSelect) select;
        return plainSelect.getWhere();
    }

    private SqlTableContext tableContext(EncryptColumnRule rule) {
        EncryptTableRule tableRule = new EncryptTableRule("user_account");
        tableRule.addColumnRule(rule);
        SqlTableContext tableContext = new SqlTableContext();
        tableContext.register("user_account", null, tableRule);
        return tableContext;
    }

    private EncryptColumnRule sameTableRule() {
        return new EncryptColumnRule(
                "phone",
                "user_account",
                "phone",
                "sm4",
                "phone_hash",
                "sm3",
                "phone_like",
                "like",
                FieldStorageMode.SAME_TABLE,
                null,
                "phone_cipher",
                null
        );
    }

    private EncryptColumnRule separateTableRule() {
        return new EncryptColumnRule(
                "phone",
                "user_account",
                "phone",
                "sm4",
                "phone_hash",
                "sm3",
                "phone_like",
                "like",
                FieldStorageMode.SEPARATE_TABLE,
                "user_phone_encrypt",
                "phone_cipher",
                "phone_hash"
        );
    }

    private Column buildColumn(Column source, String targetColumn) {
        Column column = new Column(quote(targetColumn));
        if (source.getTable() != null && source.getTable().getName() != null) {
            column.setTable(new Table(source.getTable().getName()));
        }
        return column;
    }

    private String quote(String identifier) {
        return "`" + identifier + "`";
    }
}
