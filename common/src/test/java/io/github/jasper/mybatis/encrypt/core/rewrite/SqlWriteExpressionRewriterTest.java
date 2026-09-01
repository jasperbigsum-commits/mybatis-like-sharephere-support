package io.github.jasper.mybatis.encrypt.core.rewrite;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.algorithm.support.Sm4CipherAlgorithm;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import io.github.jasper.mybatis.encrypt.core.metadata.FieldStorageMode;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.StringValue;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
@Tag("rewrite")
class SqlWriteExpressionRewriterTest {

    private final Sm4CipherAlgorithm cipherAlgorithm = new Sm4CipherAlgorithm("test-key");

    /**
     * 测试目的：验证 SQL 改写核心组件在当前语句结构下保持安全且确定的改写行为。
     * 测试场景：构造对应 SQL、加密规则和参数上下文，断言 AST 改写结果、参数绑定和安全边界。
     */
    @Test
    void shouldRewriteJdbcParameterToCipherAndReplaceBinding() {
        SqlRewriteContext context = rewriteContext(
                "INSERT INTO user_account(phone) VALUES (?)",
                "phone",
                "13800138000"
        );
        SqlWriteExpressionRewriter rewriter = newRewriter();

        WriteValue writeValue = rewriter.rewriteEncrypted(new JdbcParameter(), sameTableRule(), context);

        assertTrue(writeValue.parameterized());
        assertSame(JdbcParameter.class, writeValue.expression().getClass());
        assertEquals("13800138000", writeValue.plainValue());
        assertTrue(context.parameterMappings().get(0).getProperty().startsWith("__encrypt_generated_"));
        assertEquals("***", context.maskedParameters().values().iterator().next().value());
        assertEquals("13800138000", cipherAlgorithm.decrypt(String.valueOf(context.originalValue(0))));
    }

    /**
     * 测试目的：验证 SQL 改写核心组件在当前语句结构下保持安全且确定的改写行为。
     * 测试场景：构造对应 SQL、加密规则和参数上下文，断言 AST 改写结果、参数绑定和安全边界。
     */
    @Test
    void shouldRewriteLiteralToCipherLiteral() {
        SqlRewriteContext context = rewriteContext(
                "INSERT INTO user_account(phone) VALUES ('13800138000')",
                null,
                null
        );
        SqlWriteExpressionRewriter rewriter = newRewriter();

        WriteValue writeValue = rewriter.rewriteEncrypted(new StringValue("13800138000"), sameTableRule(), context);

        StringValue cipherLiteral = assertInstanceOf(StringValue.class, writeValue.expression());
        assertEquals("13800138000", writeValue.plainValue());
        assertEquals("13800138000", cipherAlgorithm.decrypt(cipherLiteral.getValue()));
    }

    /**
     * 测试目的：验证 SQL 改写核心组件在当前语句结构下保持安全且确定的改写行为。
     * 测试场景：构造对应 SQL、加密规则和参数上下文，断言 AST 改写结果、参数绑定和安全边界。
     */
    @Test
    void shouldRewriteSeparateTableReferenceByReplacingBinding() {
        SqlRewriteContext context = rewriteContext(
                "INSERT INTO user_account(phone) VALUES (?)",
                "phone",
                "prepared-hash-ref"
        );
        SqlWriteExpressionRewriter rewriter = newRewriter();

        Expression expression = rewriter.rewriteSeparateTableReference(new JdbcParameter(), context);

        assertSame(JdbcParameter.class, expression.getClass());
        assertTrue(context.parameterMappings().get(0).getProperty().startsWith("__encrypt_generated_"));
        assertEquals("prepared-hash-ref", context.originalValue(0));
    }

    /**
     * 测试目的：验证 SQL 改写核心组件在当前语句结构下保持安全且确定的改写行为。
     * 测试场景：构造对应 SQL、加密规则和参数上下文，断言 AST 改写结果、参数绑定和安全边界。
     */
    @Test
    void shouldBuildSyntheticShadowParameterWhenPrimaryWriteUsesParameter() {
        SqlRewriteContext context = rewriteContext(
                "INSERT INTO user_account(phone) VALUES (?)",
                "phone",
                "13800138000"
        );
        SqlWriteExpressionRewriter rewriter = newRewriter();
        WriteValue writeValue = rewriter.rewriteEncrypted(new JdbcParameter(), sameTableRule(), context);

        Expression expression = rewriter.buildShadow(writeValue, "shadow-value", MaskingMode.HASH, context);

        assertInstanceOf(JdbcParameter.class, expression);
        assertEquals(2, context.parameterMappings().size());
        assertEquals("shadow-value", context.originalValue(1));
    }

    private SqlWriteExpressionRewriter newRewriter() {
        EncryptionValueTransformer transformer = new EncryptionValueTransformer(new AlgorithmRegistry(
                Collections.singletonMap("sm4", cipherAlgorithm),
                Collections.emptyMap(),
                Collections.emptyMap()
        ));
        SqlConditionRewriter conditionRewriter = new SqlConditionRewriter(
                transformer,
                (column, target) -> column,
                (rule, scenario) -> rule.assistedQueryColumn(),
                (rule, scenario) -> rule.likeQueryColumn(),
                identifier -> identifier,
                (select, context, projectionMode, outerTableContext) -> {
                }
        );
        return new SqlWriteExpressionRewriter(transformer, conditionRewriter);
    }

    private SqlRewriteContext rewriteContext(String sql, String property, Object parameterValue) {
        Configuration configuration = new Configuration();
        BoundSql boundSql = new BoundSql(
                configuration,
                sql,
                property == null
                        ? Collections.<ParameterMapping>emptyList()
                        : Collections.singletonList(new ParameterMapping.Builder(configuration, property, String.class).build()),
                property == null ? null : Collections.singletonMap(property, parameterValue)
        );
        return new SqlRewriteContext(configuration, boundSql, new ParameterValueResolver());
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
}
