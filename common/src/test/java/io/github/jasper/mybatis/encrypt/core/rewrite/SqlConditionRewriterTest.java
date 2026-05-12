package io.github.jasper.mybatis.encrypt.core.rewrite;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.algorithm.support.NormalizedLikeQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.support.Sm3AssistedQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptTableRule;
import io.github.jasper.mybatis.encrypt.core.metadata.FieldStorageMode;
import io.github.jasper.mybatis.encrypt.util.JSqlParserSupport;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
@Tag("rewrite")
class SqlConditionRewriterTest {

    /**
     * 测试目的：验证查询条件中的加密字段会改写为辅助查询列或独立表 EXISTS 谓词。
     * 测试场景：构造等值、LIKE、空值、嵌套括号和子查询条件，断言 SQL 谓词和参数顺序保持正确。
     */
    @Test
    void shouldRewriteEqualityToAssistedColumnAndReplaceParameter() throws Exception {
        SqlConditionRewriter rewriter = newRewriter(new ArrayList<>());
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

    /**
     * 测试目的：验证查询条件中的加密字段会改写为辅助查询列或独立表 EXISTS 谓词。
     * 测试场景：构造等值、LIKE、空值、嵌套括号和子查询条件，断言 SQL 谓词和参数顺序保持正确。
     */
    @Test
    void shouldRewriteLikeConcatToLikeAndAssistedFallback() throws Exception {
        SqlConditionRewriter rewriter = newRewriter(new ArrayList<>());
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

    /**
     * 测试目的：验证业务 SQL 中把手机号拆成固定片段再 CONCAT 的场景，同表等值查询也会直接改写到 hash 列。
     * 测试场景：模拟 XML 中写死前缀和后缀来拼接完整手机号，断言改写后不再保留 CONCAT 明文表达式，而是改成可直接命中辅助列的 hash 常量。
     */
    @Test
    void shouldRewriteEqualityLiteralConcatToAssistedLiteral() throws Exception {
        SqlConditionRewriter rewriter = newRewriter(new ArrayList<>());
        SqlTableContext tableContext = tableContext(sameTableRule());

        Expression rewritten = rewriter.rewrite(
                parseWhere("SELECT id FROM user_account WHERE phone = CONCAT('138', '00138000')"),
                tableContext,
                rewriteContext("SELECT id FROM user_account WHERE phone = CONCAT('138', '00138000')",
                        Collections.<ParameterMapping>emptyList(), Collections.emptyMap())
        );

        assertTrue(rewritten.toString().contains("`phone_hash` = '"
                + new Sm3AssistedQueryAlgorithm().transform("13800138000") + "'"));
        assertFalse(rewritten.toString().contains("CONCAT("));
    }

    /**
     * 测试目的：验证 where 中对加密字段使用空串不等过滤时，也会按辅助 hash 列保持原有比较语义。
     * 测试场景：模拟业务 SQL 用 `phone <> ''` 排除空手机号，断言改写后仍是非等比较，且不会残留明文空串列判断。
     */
    @Test
    void shouldRewriteNotEqualsEmptyLiteralToAssistedLiteral() throws Exception {
        SqlConditionRewriter rewriter = newRewriter(new ArrayList<>());
        SqlTableContext tableContext = tableContext(sameTableRule());

        Expression rewritten = rewriter.rewrite(
                parseWhere("SELECT id FROM user_account WHERE phone <> ''"),
                tableContext,
                rewriteContext("SELECT id FROM user_account WHERE phone <> ''",
                        Collections.<ParameterMapping>emptyList(), Collections.emptyMap())
        );

        String rewrittenSql = rewritten.toString();
        String expectedHash = new Sm3AssistedQueryAlgorithm().transform("");
        assertTrue(rewrittenSql.contains("`phone_hash` <> '" + expectedHash + "'")
                || rewrittenSql.contains("`phone_hash` != '" + expectedHash + "'"));
        assertFalse(rewrittenSql.contains("phone <> ''"));
    }

    /**
     * 测试目的：验证联表查询下使用表别名引用加密字段做空串不等过滤时，仍会准确改写到对应别名下的辅助 hash 列。
     * 测试场景：模拟 `user_account u join order_account o` 联表并在 where 中使用 `u.phone <> ''`，断言改写结果保留别名且不再残留逻辑明文字段。
     */
    @Test
    void shouldRewriteJoinedAliasNotEqualsEmptyLiteralToAssistedLiteral() throws Exception {
        SqlConditionRewriter rewriter = newRewriter(new ArrayList<>());
        SqlTableContext tableContext = new SqlTableContext();
        EncryptTableRule tableRule = new EncryptTableRule("user_account");
        tableRule.addColumnRule(sameTableRule());
        tableContext.register("user_account", "u", tableRule);

        Expression rewritten = rewriter.rewrite(
                parseWhere("SELECT u.id FROM user_account u JOIN order_account o ON u.id = o.user_id WHERE u.phone <> ''"),
                tableContext,
                rewriteContext(
                        "SELECT u.id FROM user_account u JOIN order_account o ON u.id = o.user_id WHERE u.phone <> ''",
                        Collections.<ParameterMapping>emptyList(),
                        Collections.emptyMap()
                )
        );

        String rewrittenSql = rewritten.toString();
        String expectedHash = new Sm3AssistedQueryAlgorithm().transform("");
        assertTrue(rewrittenSql.contains("u.`phone_hash` <> '" + expectedHash + "'")
                || rewrittenSql.contains("u.`phone_hash` != '" + expectedHash + "'"));
        assertFalse(rewrittenSql.contains("u.phone <> ''"));
    }

    /**
     * 测试目的：验证联表查询里同时存在非加密字段条件和加密字段空串不等条件时，只改写加密字段部分。
     * 测试场景：模拟 `u.phone <> '' AND o.order_status = 'active'`，断言 `u.phone` 改写为 hash 比较，而 `o.order_status` 原样保留。
     */
    @Test
    void shouldRewriteOnlyEncryptedPredicateWhenJoinedPlainAndEncryptedConditionsCoexist() throws Exception {
        SqlConditionRewriter rewriter = newRewriter(new ArrayList<>());
        SqlTableContext tableContext = new SqlTableContext();
        EncryptTableRule tableRule = new EncryptTableRule("user_account");
        tableRule.addColumnRule(sameTableRule());
        tableContext.register("user_account", "u", tableRule);

        Expression rewritten = rewriter.rewrite(
                parseWhere("SELECT u.id FROM user_account u JOIN order_account o ON u.id = o.user_id "
                        + "WHERE u.phone <> '' AND o.order_status = 'active'"),
                tableContext,
                rewriteContext(
                        "SELECT u.id FROM user_account u JOIN order_account o ON u.id = o.user_id "
                                + "WHERE u.phone <> '' AND o.order_status = 'active'",
                        Collections.<ParameterMapping>emptyList(),
                        Collections.emptyMap()
                )
        );

        String rewrittenSql = rewritten.toString();
        String expectedHash = new Sm3AssistedQueryAlgorithm().transform("");
        assertTrue(rewrittenSql.contains("u.`phone_hash` <> '" + expectedHash + "'")
                || rewrittenSql.contains("u.`phone_hash` != '" + expectedHash + "'"));
        assertTrue(rewrittenSql.contains("o.order_status = 'active'"));
        assertFalse(rewrittenSql.contains("u.phone <> ''"));
    }

    /**
     * 测试目的：验证查询条件中的加密字段会改写为辅助查询列或独立表 EXISTS 谓词。
     * 测试场景：构造等值、LIKE、空值、嵌套括号和子查询条件，断言 SQL 谓词和参数顺序保持正确。
     */
    @Test
    void shouldRewriteExactLikeToLikeAndAssistedFallback() throws Exception {
        SqlConditionRewriter rewriter = newRewriter(new ArrayList<>());
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
    void shouldDegradeLikeToAssistedEqualityWhenLikeColumnIsMissing() throws Exception {
        SqlConditionRewriter rewriter = newRewriter(new ArrayList<ProjectionMode>());
        SqlTableContext tableContext = tableContext(sameTableRuleWithoutLikeColumn());
        SqlRewriteContext context = rewriteContext("SELECT id FROM user_account WHERE phone LIKE ?",
                Collections.singletonList(new ParameterMapping.Builder(new Configuration(), "phone", String.class).build()),
                Collections.<String, Object>singletonMap("phone", "%13800138000%"));

        Expression rewritten = rewriter.rewrite(parseWhere("SELECT id FROM user_account WHERE phone LIKE ?"),
                tableContext, context);

        assertTrue(rewritten.toString().contains("`phone_hash` = ?"));
        assertFalse(rewritten.toString().contains("LIKE"));
        assertEquals(1, context.parameterMappings().size());
        assertEquals(new Sm3AssistedQueryAlgorithm().transform("13800138000"), context.originalValue(0));
    }

    /**
     * 测试目的：验证后台模糊检索 SQL 即使把完整手机号写成固定值 CONCAT，也会同时产出 LIKE 列查询和 hash 精确兜底。
     * 测试场景：模拟运营列表查询把完整关键字拆成多个常量片段，断言 LIKE 条件、辅助 hash 条件以及常量折叠能够一次完成。
     */
    @Test
    void shouldRewriteLikeLiteralConcatToLikeAndAssistedFallback() throws Exception {
        SqlConditionRewriter rewriter = newRewriter(new ArrayList<>());
        SqlTableContext tableContext = tableContext(sameTableRule());

        Expression rewritten = rewriter.rewrite(
                parseWhere("SELECT id FROM user_account WHERE phone LIKE CONCAT('138', '00138000')"),
                tableContext,
                rewriteContext("SELECT id FROM user_account WHERE phone LIKE CONCAT('138', '00138000')",
                        Collections.<ParameterMapping>emptyList(), Collections.emptyMap())
        );

        assertTrue(rewritten.toString().contains("`phone_like` LIKE '13800138000'"));
        assertTrue(rewritten.toString().contains("`phone_hash` = '"
                + new Sm3AssistedQueryAlgorithm().transform("13800138000") + "'"));
        assertTrue(rewritten.toString().contains(" OR "));
        assertFalse(rewritten.toString().contains("CONCAT("));
    }

    /**
     * 测试目的：验证查询条件中的加密字段会改写为辅助查询列或独立表 EXISTS 谓词。
     * 测试场景：构造等值、LIKE、空值、嵌套括号和子查询条件，断言 SQL 谓词和参数顺序保持正确。
     */
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
        assertTrue(rewritten.toString().contains("`phone` = ?"));
        assertTrue(rewritten.toString().contains(" OR "));
        assertEquals(2, context.parameterMappings().size());
        assertEquals("%abc%", context.originalValue(0));
        assertEquals(new Sm3AssistedQueryAlgorithm().transform("AbC"), context.originalValue(1));
    }

    /**
     * 测试目的：验证独立表模式下批量按证件号、手机号等主表引用字段过滤时，IN 列表可以直接改写为主表 hash/ref 列比较。
     * 测试场景：模拟独立表字段使用固定值、CONCAT 固定表达式和预编译参数混合查询，断言不再生成 EXISTS，所有候选值都转换为 hash。
     */
    @Test
    void shouldRewriteSeparateTableInListToMainReferenceHashOperands() throws Exception {
        SqlConditionRewriter rewriter = newRewriter(new ArrayList<ProjectionMode>());
        SqlTableContext tableContext = tableContext(separateTableRule());
        SqlRewriteContext context = rewriteContext(
                "SELECT id FROM user_account WHERE phone IN ('13800138000', CONCAT('139', '00139000'), ?)",
                Collections.singletonList(new ParameterMapping.Builder(new Configuration(), "phone", String.class).build()),
                Collections.<String, Object>singletonMap("phone", "13700137000")
        );

        Expression rewritten = rewriter.rewrite(
                parseWhere("SELECT id FROM user_account WHERE phone IN ('13800138000', CONCAT('139', '00139000'), ?)"),
                tableContext,
                context
        );

        assertTrue(rewritten.toString().contains("`phone` IN"));
        assertFalse(rewritten.toString().contains("EXISTS"));
        assertTrue(rewritten.toString().contains("'" + new Sm3AssistedQueryAlgorithm().transform("13800138000") + "'"));
        assertTrue(rewritten.toString().contains("'" + new Sm3AssistedQueryAlgorithm().transform("13900139000") + "'"));
        assertFalse(rewritten.toString().contains("CONCAT("));
        assertEquals(1, context.parameterMappings().size());
        assertTrue(context.parameterMappings().get(0).getProperty().startsWith("__encrypt_generated_"));
        assertEquals(new Sm3AssistedQueryAlgorithm().transform("13700137000"), context.originalValue(0));
    }

    /**
     * 测试目的：验证独立表模式下 LIKE 精确匹配不必再进入外表 LIKE 列，直接使用主表 hash/ref 列即可完成查询。
     * 测试场景：模拟用户输入完整证件号、手机号且 SQL 使用 LIKE 但没有通配符，断言改写结果为主表引用列等值 hash 条件。
     */
    @Test
    void shouldRewriteSeparateTableExactLikeToMainReferenceHashPredicate() throws Exception {
        SqlConditionRewriter rewriter = newRewriter(new ArrayList<ProjectionMode>());
        SqlTableContext tableContext = tableContext(separateTableRule());
        SqlRewriteContext context = rewriteContext("SELECT id FROM user_account WHERE phone LIKE ?",
                Collections.singletonList(new ParameterMapping.Builder(new Configuration(), "phone", String.class).build()),
                Collections.<String, Object>singletonMap("phone", "13800138000"));

        Expression rewritten = rewriter.rewrite(
                parseWhere("SELECT id FROM user_account WHERE phone LIKE ?"),
                tableContext,
                context
        );

        assertTrue(rewritten.toString().contains("`phone` = ?"));
        assertFalse(rewritten.toString().contains("EXISTS"));
        assertEquals(1, context.parameterMappings().size());
        assertEquals(new Sm3AssistedQueryAlgorithm().transform("13800138000"), context.originalValue(0));
    }

    /**
     * 测试目的：验证 HAVING 子句会复用 WHERE 的同表等值改写规则，按辅助 hash 列完成筛选。
     * 测试场景：模拟按手机号分组后的统计查询在 HAVING 中继续按手机号精确过滤，断言改写后命中 phone_hash 且参数被转换为 hash。
     */
    @Test
    void shouldRewriteHavingEqualityToAssistedColumn() throws Exception {
        SqlConditionRewriter rewriter = newRewriter(new ArrayList<ProjectionMode>());
        SqlTableContext tableContext = tableContext(sameTableRule());
        SqlRewriteContext context = rewriteContext(
                "SELECT phone, COUNT(*) total FROM user_account GROUP BY phone HAVING phone = ?",
                Collections.singletonList(new ParameterMapping.Builder(new Configuration(), "phone", String.class).build()),
                Collections.<String, Object>singletonMap("phone", "13800138000")
        );

        Expression rewritten = rewriter.rewrite(
                parseHaving("SELECT phone, COUNT(*) total FROM user_account GROUP BY phone HAVING phone = ?"),
                tableContext,
                context
        );

        assertTrue(rewritten.toString().contains("`phone_hash` = ?"));
        assertEquals(1, context.parameterMappings().size());
        assertEquals(new Sm3AssistedQueryAlgorithm().transform("13800138000"), context.originalValue(0));
    }

    /**
     * 测试目的：验证 HAVING 中的名单过滤也会复用 IN 列表批量 hash 改写能力。
     * 测试场景：模拟分组统计后再用固定值、固定 CONCAT 和预编译参数混合名单过滤手机号，断言每个候选值都被转换为 hash 查询项。
     */
    @Test
    void shouldRewriteHavingInListLiteralConcatAndParameterToHashOperands() throws Exception {
        SqlConditionRewriter rewriter = newRewriter(new ArrayList<ProjectionMode>());
        SqlTableContext tableContext = tableContext(sameTableRule());
        SqlRewriteContext context = rewriteContext(
                "SELECT phone, COUNT(*) total FROM user_account GROUP BY phone HAVING phone IN ('13800138000', CONCAT('139', '00139000'), ?)",
                Collections.singletonList(new ParameterMapping.Builder(new Configuration(), "phone", String.class).build()),
                Collections.<String, Object>singletonMap("phone", "13700137000")
        );

        Expression rewritten = rewriter.rewrite(
                parseHaving("SELECT phone, COUNT(*) total FROM user_account GROUP BY phone HAVING phone IN ('13800138000', CONCAT('139', '00139000'), ?)"),
                tableContext,
                context
        );

        assertTrue(rewritten.toString().contains("`phone_hash` IN"));
        assertTrue(rewritten.toString().contains("'" + new Sm3AssistedQueryAlgorithm().transform("13800138000") + "'"));
        assertTrue(rewritten.toString().contains("'" + new Sm3AssistedQueryAlgorithm().transform("13900139000") + "'"));
        assertFalse(rewritten.toString().contains("CONCAT("));
        assertEquals(1, context.parameterMappings().size());
        assertTrue(context.parameterMappings().get(0).getProperty().startsWith("__encrypt_generated_"));
        assertEquals(new Sm3AssistedQueryAlgorithm().transform("13700137000"), context.originalValue(0));
    }

    /**
     * 测试目的：验证独立表模式下 HAVING 精确 LIKE 也会直接命中主表 hash/ref 字段，而不是回退到外表 EXISTS。
     * 测试场景：模拟按证件号或手机号引用列分组后，HAVING 使用无通配符 LIKE 精确过滤，断言改写结果为主表引用列等值 hash 条件。
     */
    @Test
    void shouldRewriteSeparateTableHavingExactLikeToMainReferenceHashPredicate() throws Exception {
        SqlConditionRewriter rewriter = newRewriter(new ArrayList<ProjectionMode>());
        SqlTableContext tableContext = tableContext(separateTableRule());
        SqlRewriteContext context = rewriteContext(
                "SELECT phone, COUNT(*) total FROM user_account GROUP BY phone HAVING phone LIKE ?",
                Collections.singletonList(new ParameterMapping.Builder(new Configuration(), "phone", String.class).build()),
                Collections.<String, Object>singletonMap("phone", "13800138000")
        );

        Expression rewritten = rewriter.rewrite(
                parseHaving("SELECT phone, COUNT(*) total FROM user_account GROUP BY phone HAVING phone LIKE ?"),
                tableContext,
                context
        );

        assertTrue(rewritten.toString().contains("`phone` = ?"));
        assertFalse(rewritten.toString().contains("EXISTS"));
        assertEquals(1, context.parameterMappings().size());
        assertEquals(new Sm3AssistedQueryAlgorithm().transform("13800138000"), context.originalValue(0));
    }

    /**
     * 测试目的：验证 SELECT 投影改写能正确暴露密文列别名并避免重复投影。
     * 测试场景：构造通配符、多表、派生表和 UNION 查询，断言投影列、隐藏辅助列和别名处理符合预期。
     */
    @Test
    void shouldDispatchComparisonProjectionModeForEncryptedInSubquery() throws Exception {
        List<ProjectionMode> dispatchedModes = new ArrayList<>();
        SqlConditionRewriter rewriter = newRewriter(dispatchedModes);
        SqlTableContext tableContext = tableContext(sameTableRule());
        SqlRewriteContext context = rewriteContext("SELECT id FROM user_account WHERE phone IN (SELECT phone FROM user_account)",
                Collections.emptyList(),
                Collections.emptyMap());

        Expression rewritten = rewriter.rewrite(
                parseWhere("SELECT id FROM user_account WHERE phone IN (SELECT phone FROM user_account)"),
                tableContext,
                context
        );

        assertTrue(rewritten.toString().contains("`phone_hash` IN"));
        assertEquals(Collections.singletonList(ProjectionMode.COMPARISON), dispatchedModes);
    }

    /**
     * 测试目的：验证批量名单筛选场景中，IN 列表里的固定值、固定值 CONCAT 和预编译参数都会统一改写为 hash 查询项。
     * 测试场景：模拟导出筛选 SQL 同时混用字面量和参数，断言每个候选值都完成 hash 转换，并且参数槽位不会因为表达式折叠而错位。
     */
    @Test
    void shouldRewriteInListLiteralConcatAndParameterToHashOperands() throws Exception {
        SqlConditionRewriter rewriter = newRewriter(new ArrayList<ProjectionMode>());
        SqlTableContext tableContext = tableContext(sameTableRule());
        SqlRewriteContext context = rewriteContext(
                "SELECT id FROM user_account WHERE phone IN ('13800138000', CONCAT('139', '00139000'), ?)",
                Collections.singletonList(new ParameterMapping.Builder(new Configuration(), "phone", String.class).build()),
                Collections.<String, Object>singletonMap("phone", "13700137000")
        );

        Expression rewritten = rewriter.rewrite(
                parseWhere("SELECT id FROM user_account WHERE phone IN ('13800138000', CONCAT('139', '00139000'), ?)"),
                tableContext,
                context
        );

        assertTrue(rewritten.toString().contains("`phone_hash` IN"));
        assertTrue(rewritten.toString().contains("'" + new Sm3AssistedQueryAlgorithm().transform("13800138000") + "'"));
        assertTrue(rewritten.toString().contains("'" + new Sm3AssistedQueryAlgorithm().transform("13900139000") + "'"));
        assertFalse(rewritten.toString().contains("CONCAT("));
        assertEquals(1, context.parameterMappings().size());
        assertTrue(context.parameterMappings().get(0).getProperty().startsWith("__encrypt_generated_"));
        assertEquals(new Sm3AssistedQueryAlgorithm().transform("13700137000"), context.originalValue(0));
    }

    /**
     * 测试目的：验证查询条件中的加密字段会改写为辅助查询列或独立表 EXISTS 谓词。
     * 测试场景：构造等值、LIKE、空值、嵌套括号和子查询条件，断言 SQL 谓词和参数顺序保持正确。
     */
    @Test
    void shouldRewriteSeparateTableEqualityToExistsSubquery() throws Exception {
        SqlConditionRewriter rewriter = newRewriter(new ArrayList<>());
        SqlTableContext tableContext = tableContext(separateTableRule());
        SqlRewriteContext context = rewriteContext("SELECT id FROM user_account WHERE phone = ?",
                Collections.singletonList(new ParameterMapping.Builder(new Configuration(), "phone", String.class).build()),
                Collections.<String, Object>singletonMap("phone", "13800138000"));

        Expression rewritten = rewriter.rewrite(parseWhere("SELECT id FROM user_account WHERE phone = ?"), tableContext, context);

        assertTrue(rewritten.toString().contains("EXISTS"));
        assertTrue(rewritten.toString().contains("`user_phone_encrypt`"));
        assertTrue(rewritten.toString().contains("`phone_hash` = ?"));
    }

    @Test
    void shouldRewriteSameTableEncryptedColumnEqualityToAssistedColumns() throws Exception {
        SqlConditionRewriter rewriter = newRewriter(new ArrayList<>());
        SqlTableContext tableContext = tableContext(sameTableRule(), sameTableBackupRule());
        SqlRewriteContext context = rewriteContext("SELECT id FROM user_account WHERE phone = backup_phone",
                Collections.emptyList(), Collections.emptyMap());

        Expression rewritten = rewriter.rewrite(
                parseWhere("SELECT id FROM user_account WHERE phone = backup_phone"),
                tableContext,
                context
        );

        assertEquals("`phone_hash` = `backup_phone_hash`", rewritten.toString());
        assertEquals(0, context.parameterMappings().size());
    }

    @Test
    void shouldRewriteSeparateTableEncryptedColumnEqualityToReferenceColumns() throws Exception {
        SqlConditionRewriter rewriter = newRewriter(new ArrayList<ProjectionMode>());
        SqlTableContext tableContext = tableContext(separateTableRule(), separateTableBackupRule());
        SqlRewriteContext context = rewriteContext("SELECT id FROM user_account WHERE phone = backup_phone",
                Collections.<ParameterMapping>emptyList(), Collections.emptyMap());

        Expression rewritten = rewriter.rewrite(
                parseWhere("SELECT id FROM user_account WHERE phone = backup_phone"),
                tableContext,
                context
        );

        assertEquals("`phone` = `backup_phone`", rewritten.toString());
        assertFalse(rewritten.toString().contains("EXISTS"));
        assertEquals(0, context.parameterMappings().size());
    }

    @Test
    void shouldRewriteSeparateTableEncryptedColumnEqualityWithParenthesesToReferenceColumns() throws Exception {
        SqlConditionRewriter rewriter = newRewriter(new ArrayList<ProjectionMode>());
        SqlTableContext tableContext = tableContext(separateTableRule(), separateTableBackupRule());
        SqlRewriteContext context = rewriteContext("SELECT id FROM user_account WHERE phone = (backup_phone)",
                Collections.emptyList(), Collections.emptyMap());

        Expression rewritten = rewriter.rewrite(
                parseWhere("SELECT id FROM user_account WHERE phone = (backup_phone)"),
                tableContext,
                context
        );

        assertEquals("`phone` = `backup_phone`", rewritten.toString());
        assertFalse(rewritten.toString().contains("EXISTS"));
    }

    @Test
    void shouldRewriteSeparateTableEncryptedColumnEqualityWithAliasAndParenthesesToReferenceColumns() throws Exception {
        SqlConditionRewriter rewriter = newRewriter(new ArrayList<>());
        SqlTableContext tableContext = new SqlTableContext();
        EncryptTableRule tableRule = new EncryptTableRule("user_account");
        tableRule.addColumnRule(separateTableRule());
        tableRule.addColumnRule(separateTableBackupRule());
        tableContext.register("user_account", "u", tableRule);
        SqlRewriteContext context = rewriteContext("SELECT id FROM user_account u WHERE u.phone = (u.backup_phone)",
                Collections.emptyList(), Collections.emptyMap());

        Expression rewritten = rewriter.rewrite(
                parseWhere("SELECT id FROM user_account u WHERE u.phone = (u.backup_phone)"),
                tableContext,
                context
        );

        assertEquals("u.`phone` = u.`backup_phone`", rewritten.toString());
        assertFalse(rewritten.toString().contains("EXISTS"));
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
                (select, context, projectionMode, outerTableContext) -> dispatchedModes.add(projectionMode)
        );
    }

    private SqlRewriteContext rewriteContext(String sql, List<ParameterMapping> parameterMappings, Object parameterObject) {
        Configuration configuration = new Configuration();
        BoundSql boundSql = new BoundSql(configuration, sql, parameterMappings, parameterObject);
        return new SqlRewriteContext(configuration, boundSql, new ParameterValueResolver());
    }

    private Expression parseWhere(String sql) throws Exception {
        Statement statement = JSqlParserSupport.parseStatement(sql);
        Select select = (Select) statement;
        PlainSelect plainSelect = (PlainSelect) select;
        return plainSelect.getWhere();
    }

    private Expression parseHaving(String sql) throws Exception {
        Statement statement = JSqlParserSupport.parseStatement(sql);
        Select select = (Select) statement;
        PlainSelect plainSelect = (PlainSelect) select;
        return plainSelect.getHaving();
    }

    private SqlTableContext tableContext(EncryptColumnRule... rules) {
        EncryptTableRule tableRule = new EncryptTableRule("user_account");
        for (EncryptColumnRule rule : rules) {
            tableRule.addColumnRule(rule);
        }
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

    private EncryptColumnRule sameTableRuleWithoutLikeColumn() {
        return new EncryptColumnRule(
                "phone",
                "user_account",
                "phone",
                "sm4",
                "phone_hash",
                "sm3",
                null,
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

    private EncryptColumnRule sameTableBackupRule() {
        return new EncryptColumnRule(
                "backupPhone",
                "user_account",
                "backup_phone",
                "sm4",
                "backup_phone_hash",
                "sm3",
                "backup_phone_like",
                "like",
                FieldStorageMode.SAME_TABLE,
                null,
                "backup_phone_cipher",
                null
        );
    }

    private EncryptColumnRule separateTableBackupRule() {
        return new EncryptColumnRule(
                "backupPhone",
                "user_account",
                "backup_phone",
                "sm4",
                "backup_phone_hash",
                "sm3",
                "backup_phone_like",
                "like",
                FieldStorageMode.SEPARATE_TABLE,
                "user_backup_phone_encrypt",
                "backup_phone_cipher",
                "backup_phone_hash"
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
