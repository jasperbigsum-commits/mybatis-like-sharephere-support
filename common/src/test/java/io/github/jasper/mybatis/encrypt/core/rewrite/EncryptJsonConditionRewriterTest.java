package io.github.jasper.mybatis.encrypt.core.rewrite;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.algorithm.support.Sm3AssistedQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptJsonFieldRule;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptJsonPathRule;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptTableRule;
import io.github.jasper.mybatis.encrypt.util.JSqlParserSupport;
import net.sf.jsqlparser.expression.Expression;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
@Tag("rewrite")
class EncryptJsonConditionRewriterTest {

    @Test
    void shouldRewriteJsonExtractEqualityOperandToHash() throws Exception {
        SqlConditionRewriter rewriter = newRewriter();
        SqlTableContext tableContext = tableContext(jsonFieldRule());
        SqlRewriteContext context = rewriteContext(
                "SELECT id FROM user_account WHERE JSON_EXTRACT(profile_json, '$.phone') = ?",
                Collections.singletonList(new ParameterMapping.Builder(new Configuration(), "phone", String.class).build()),
                Collections.<String, Object>singletonMap("phone", "13800138000")
        );

        Expression rewritten = rewriter.rewrite(
                parseWhere("SELECT id FROM user_account WHERE JSON_EXTRACT(profile_json, '$.phone') = ?"),
                tableContext,
                context
        );

        assertTrue(rewritten.toString().contains("JSON_EXTRACT"));
        assertTrue(rewritten.toString().contains("= ?"));
        assertEquals(new Sm3AssistedQueryAlgorithm().transform("13800138000"), context.originalValue(0));
    }

    @Test
    void shouldRewriteJsonExtractInOperandsToHash() throws Exception {
        SqlConditionRewriter rewriter = newRewriter();
        SqlTableContext tableContext = tableContext(jsonFieldRule());
        SqlRewriteContext context = rewriteContext(
                "SELECT id FROM user_account WHERE JSON_EXTRACT(profile_json, '$.phone') IN (?, ?)",
                java.util.Arrays.asList(
                        new ParameterMapping.Builder(new Configuration(), "first", String.class).build(),
                        new ParameterMapping.Builder(new Configuration(), "second", String.class).build()
                ),
                new LinkedHashMap<String, Object>() {{
                    put("first", "13800138000");
                    put("second", "13900139000");
                }}
        );

        Expression rewritten = rewriter.rewrite(
                parseWhere("SELECT id FROM user_account WHERE JSON_EXTRACT(profile_json, '$.phone') IN (?, ?)"),
                tableContext,
                context
        );

        assertTrue(rewritten.toString().contains("JSON_EXTRACT"));
        assertTrue(rewritten.toString().contains("IN"));
        assertEquals(new Sm3AssistedQueryAlgorithm().transform("13800138000"), context.originalValue(0));
        assertEquals(new Sm3AssistedQueryAlgorithm().transform("13900139000"), context.originalValue(1));
    }

    private SqlConditionRewriter newRewriter() {
        EncryptionValueTransformer transformer = new EncryptionValueTransformer(new AlgorithmRegistry(
                Collections.emptyMap(),
                Collections.singletonMap("sm3", new Sm3AssistedQueryAlgorithm()),
                Collections.emptyMap()
        ));
        return new SqlConditionRewriter(
                transformer,
                (column, target) -> column,
                (rule, scenario) -> rule.assistedQueryColumn(),
                (rule, scenario) -> rule.likeQueryColumn(),
                identifier -> identifier,
                (select, context, projectionMode, outerTableContext) -> {
                }
        );
    }

    private SqlRewriteContext rewriteContext(String sql,
                                             java.util.List<ParameterMapping> parameterMappings,
                                             Object parameterObject) {
        Configuration configuration = new Configuration();
        BoundSql boundSql = new BoundSql(configuration, sql, parameterMappings, parameterObject);
        return new SqlRewriteContext(configuration, boundSql, new ParameterValueResolver());
    }

    private Expression parseWhere(String sql) throws Exception {
        return ((net.sf.jsqlparser.statement.select.PlainSelect)
                (net.sf.jsqlparser.statement.select.Select) JSqlParserSupport.parseStatement(sql)).getWhere();
    }

    private SqlTableContext tableContext(EncryptJsonFieldRule jsonFieldRule) {
        EncryptTableRule tableRule = new EncryptTableRule("user_account");
        tableRule.addJsonFieldRule(jsonFieldRule);
        SqlTableContext tableContext = new SqlTableContext();
        tableContext.register("user_account", null, tableRule);
        return tableContext;
    }

    private EncryptJsonFieldRule jsonFieldRule() {
        return new EncryptJsonFieldRule(
                "profileJson",
                "user_account",
                "profile_json",
                "sm4",
                "sm3",
                Collections.singletonList(new EncryptJsonPathRule(
                        "$.phone",
                        "phone_encrypt",
                        "id",
                        "phone_hash",
                        "phone_cipher",
                        "sm4",
                        "sm3"
                ))
        );
    }
}
