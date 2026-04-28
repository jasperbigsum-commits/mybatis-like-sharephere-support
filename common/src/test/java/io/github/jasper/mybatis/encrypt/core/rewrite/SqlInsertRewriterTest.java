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

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

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

    /**
     * 测试目的：验证批量插入时多行 VALUES 中的加密列和影子列全部被正确改写，不会因行数大于列数而越界。
     * 测试场景：两行批量 INSERT，第一列加密，第二列非加密，断言改写后的列列表一致且每行表达式数量正确。
     */
    @Test
    void shouldRewriteMultiRowInsertWithEncryptedAndNonEncryptedColumns() throws Exception {
        SqlInsertRewriter rewriter = newInsertRewriter();
        EncryptTableRule tableRule = new EncryptTableRule("user_account");
        tableRule.addColumnRule(sameTableRule());
        Configuration configuration = new Configuration();
        Map<String, Object> parameterObject = new LinkedHashMap<>();
        parameterObject.put("phone", "13800138000");
        parameterObject.put("name", "Alice");
        parameterObject.put("phone2", "13900139000");
        parameterObject.put("name2", "Bob");
        SqlRewriteContext context = rewriteContext(
                "INSERT INTO user_account(phone, name) VALUES (?, ?), (?, ?)",
                Arrays.asList(
                        new ParameterMapping.Builder(configuration, "phone", String.class).build(),
                        new ParameterMapping.Builder(configuration, "name", String.class).build(),
                        new ParameterMapping.Builder(configuration, "phone2", String.class).build(),
                        new ParameterMapping.Builder(configuration, "name2", String.class).build()
                ),
                parameterObject
        );
        Insert insert = parseInsert("INSERT INTO user_account(phone, name) VALUES (?, ?), (?, ?)");

        boolean changed = rewriter.rewrite(insert, tableRule, context);

        assertTrue(changed);
        String rewrittenSql = insert.toString();
        assertTrue(rewrittenSql.contains("`phone_cipher`"));
        assertTrue(rewrittenSql.contains("`phone_hash`"));
        assertTrue(rewrittenSql.contains("`phone_like`"));
        assertTrue(rewrittenSql.contains(", name)"));
        assertEquals(8, context.parameterMappings().size());
    }

    /**
     * 测试目的：验证批量插入中抛出运行时函数/常量（非占位符）的列不会导致越界，且函数表达式被直接透传。
     * 测试场景：两行批量 INSERT 中使用 {@code now()} 表达式，断言改写后 now() 仍然保留在每行中且列列表正确。
     */
    @Test
    void shouldRewriteMultiRowInsertWithFunctionExpressions() throws Exception {
        SqlInsertRewriter rewriter = newInsertRewriter();
        EncryptTableRule tableRule = new EncryptTableRule("user_account");
        tableRule.addColumnRule(sameTableRule());
        Configuration configuration = new Configuration();
        Map<String, Object> parameterObject = new LinkedHashMap<>();
        parameterObject.put("phone", "13800138000");
        parameterObject.put("phone2", "13900139000");
        SqlRewriteContext context = rewriteContext(
                "INSERT INTO user_account(phone, name, created_at) VALUES (?, 'Alice', now()), (?, 'Bob', now())",
                Arrays.asList(
                        new ParameterMapping.Builder(configuration, "phone", String.class).build(),
                        new ParameterMapping.Builder(configuration, "phone2", String.class).build()
                ),
                parameterObject
        );
        Insert insert = parseInsert(
                "INSERT INTO user_account(phone, name, created_at) VALUES (?, 'Alice', now()), (?, 'Bob', now())");

        boolean changed = rewriter.rewrite(insert, tableRule, context);

        assertTrue(changed);
        String rewrittenSql = insert.toString();
        assertTrue(rewrittenSql.contains("`phone_cipher`"));
        assertTrue(rewrittenSql.contains("`phone_hash`"));
        assertTrue(rewrittenSql.contains("`phone_like`"));
        assertTrue(rewrittenSql.contains(", name, "));
        assertTrue(rewrittenSql.contains(", created_at)"));
        assertTrue(rewrittenSql.contains("now()"));
        assertEquals(6, context.parameterMappings().size());
    }

    /**
     * 测试目的：验证批量插入中没有加密列时直接返回 false，不修改 AST。
     * 测试场景：两行批量 INSERT 全为非加密列，断言 rewritten 返回 false。
     */
    @Test
    void shouldReturnFalseForMultiRowInsertWithoutEncryptedColumns() throws Exception {
        SqlInsertRewriter rewriter = newInsertRewriter();
        EncryptTableRule tableRule = new EncryptTableRule("user_account");
        Configuration configuration = new Configuration();
        Map<String, Object> parameterObject = new LinkedHashMap<>();
        parameterObject.put("name", "Alice");
        parameterObject.put("name2", "Bob");
        SqlRewriteContext context = rewriteContext(
                "INSERT INTO user_account(name) VALUES (?), (?)",
                Arrays.asList(
                        new ParameterMapping.Builder(configuration, "name", String.class).build(),
                        new ParameterMapping.Builder(configuration, "name2", String.class).build()
                ),
                parameterObject
        );
        Insert insert = parseInsert("INSERT INTO user_account(name) VALUES (?), (?)");

        boolean changed = rewriter.rewrite(insert, tableRule, context);

        assertFalse(changed);
    }

    /**
     * 测试目的：验证多行批量插入中独立表（separate table）加密列只改写引用，不追加影子列。
     * 测试场景：两行批量 INSERT 中使用独立表加密字段，断言源列保留、引用被透传、无影子列追加。
     */
    @Test
    void shouldRewriteMultiRowInsertWithSeparateTableField() throws Exception {
        SqlInsertRewriter rewriter = newInsertRewriter();
        EncryptTableRule tableRule = new EncryptTableRule("user_account");
        tableRule.addColumnRule(separateTableRule());
        Configuration configuration = new Configuration();
        Map<String, Object> parameterObject = new LinkedHashMap<>();
        parameterObject.put("idCard", "prepared-hash-1");
        parameterObject.put("idCard2", "prepared-hash-2");
        SqlRewriteContext context = rewriteContext(
                "INSERT INTO user_account(id_card) VALUES (?), (?)",
                Arrays.asList(
                        new ParameterMapping.Builder(configuration, "idCard", String.class).build(),
                        new ParameterMapping.Builder(configuration, "idCard2", String.class).build()
                ),
                parameterObject
        );
        Insert insert = parseInsert("INSERT INTO user_account(id_card) VALUES (?), (?)");

        boolean changed = rewriter.rewrite(insert, tableRule, context);

        assertTrue(changed);
        String rewrittenSql = insert.toString();
        assertTrue(rewrittenSql.contains("id_card"));
        assertTrue(rewrittenSql.contains("VALUES (?)"), "Expected VALUES (?) not found in: " + rewrittenSql);
        assertEquals(2, insert.getValues().getExpressions().size(), "Expected 2 rows in VALUES");
        assertEquals(2, context.parameterMappings().size());
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
