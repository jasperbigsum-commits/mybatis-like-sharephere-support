package io.github.jasper.mybatis.encrypt.core.rewrite;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.algorithm.support.NormalizedLikeQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.support.Sm3AssistedQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.support.Sm4CipherAlgorithm;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptTableRule;
import io.github.jasper.mybatis.encrypt.core.metadata.FieldStorageMode;
import io.github.jasper.mybatis.encrypt.util.JSqlParserSupport;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.insert.Insert;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
@Tag("rewrite")
class SqlInsertRewriterTest {

    /**
     * 测试目的：验证写入类 SQL 会把明文字段改写为密文列、辅助查询列、LIKE 列和脱敏列。
     * 测试场景：构造 INSERT/UPDATE/DELETE 语句和 MyBatis 参数绑定，断言改写后的 SQL、生成参数和写入列集合正确。
     */
    @Test
    void shouldRewriteEncryptedInsertColumnsAndShadowValues() throws Exception {
        SqlInsertRewriter rewriter = newInsertRewriter();
        EncryptTableRule tableRule = new EncryptTableRule("user_account");
        tableRule.addColumnRule(sameTableRule());
        Configuration configuration = new Configuration();
        Map<String, Object> parameterObject = new LinkedHashMap<>();
        parameterObject.put("phone", "13800138000");
        parameterObject.put("name", "Alice");
        SqlRewriteContext context = rewriteContext(
                "INSERT INTO user_account(phone, name) VALUES (?, ?)",
                Arrays.asList(
                        new ParameterMapping.Builder(configuration, "phone", String.class).build(),
                        new ParameterMapping.Builder(configuration, "name", String.class).build()
                ),
                parameterObject
        );
        Insert insert = parseInsert("INSERT INTO user_account(phone, name) VALUES (?, ?)");

        boolean changed = rewriter.rewrite(insert, tableRule, context);

        assertTrue(changed);
        assertTrue(insert.toString().contains("`phone_cipher`"));
        assertTrue(insert.toString().contains("`phone_hash`"));
        assertTrue(insert.toString().contains("`phone_like`"));
        assertEquals(4, context.parameterMappings().size());
    }

    /**
     * 测试目的：验证写入类 SQL 会把明文字段改写为密文列、辅助查询列、LIKE 列和脱敏列。
     * 测试场景：构造 INSERT/UPDATE/DELETE 语句和 MyBatis 参数绑定，断言改写后的 SQL、生成参数和写入列集合正确。
     */
    @Test
    void shouldRewriteSeparateTableInsertReferenceOnly() throws Exception {
        SqlInsertRewriter rewriter = newInsertRewriter();
        EncryptTableRule tableRule = new EncryptTableRule("user_account");
        tableRule.addColumnRule(separateTableRule());
        SqlRewriteContext context = rewriteContext(
                "INSERT INTO user_account(id_card) VALUES (?)",
                Collections.singletonList(new ParameterMapping.Builder(new Configuration(), "idCard", String.class).build()),
                Collections.singletonMap("idCard", "prepared-hash-ref")
        );
        Insert insert = parseInsert("INSERT INTO user_account(id_card) VALUES (?)");

        boolean changed = rewriter.rewrite(insert, tableRule, context);

        assertTrue(changed);
        assertTrue(insert.toString().contains("id_card"));
        assertEquals(1, context.parameterMappings().size());
        assertEquals("prepared-hash-ref", context.originalValue(0));
    }

    private SqlInsertRewriter newInsertRewriter() {
        EncryptionValueTransformer transformer = new EncryptionValueTransformer(new AlgorithmRegistry(
                Collections.singletonMap("sm4", new Sm4CipherAlgorithm("test-key")),
                Collections.singletonMap("sm3", new Sm3AssistedQueryAlgorithm()),
                Collections.singletonMap("like", new NormalizedLikeQueryAlgorithm())
        ));
        SqlConditionRewriter conditionRewriter = new SqlConditionRewriter(
                transformer,
                (column, target) -> column,
                (rule, scenario) -> rule.assistedQueryColumn(),
                (rule, scenario) -> rule.likeQueryColumn(),
                this::quote,
                (select, context, projectionMode) -> {
                }
        );
        SqlWriteExpressionRewriter writeExpressionRewriter = new SqlWriteExpressionRewriter(transformer, conditionRewriter);
        return new SqlInsertRewriter(writeExpressionRewriter, transformer, this::quote);
    }

    private SqlRewriteContext rewriteContext(String sql, List<ParameterMapping> parameterMappings, Object parameterObject) {
        Configuration configuration = new Configuration();
        BoundSql boundSql = new BoundSql(configuration, sql, parameterMappings, parameterObject);
        return new SqlRewriteContext(configuration, boundSql, new ParameterValueResolver());
    }

    private Insert parseInsert(String sql) throws Exception {
        Statement statement = JSqlParserSupport.parseStatement(sql);
        return (Insert) statement;
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
                "idCard",
                "user_account",
                "id_card",
                "sm4",
                "id_card_hash",
                "sm3",
                "id_card_like",
                "like",
                FieldStorageMode.SEPARATE_TABLE,
                "user_id_card_encrypt",
                "id_card_cipher",
                "id_card_hash"
        );
    }

    private String quote(String identifier) {
        return "`" + identifier + "`";
    }
}
