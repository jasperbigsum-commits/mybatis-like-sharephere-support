package io.github.jasper.mybatis.encrypt.core.rewrite;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.algorithm.support.NormalizedLikeQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.support.Sm3AssistedQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.support.Sm4CipherAlgorithm;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptTableRule;
import io.github.jasper.mybatis.encrypt.core.metadata.FieldStorageMode;
import io.github.jasper.mybatis.encrypt.util.JSqlParserSupport;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.update.Update;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
@Tag("rewrite")
class SqlUpdateSetRewriterTest {

    @Test
    void shouldRewriteEncryptedAssignmentsToStorageAndShadowColumns() throws Exception {
        SqlUpdateSetRewriter rewriter = newUpdateSetRewriter();
        SqlTableContext tableContext = tableContext(sameTableRule());
        SqlRewriteContext context = rewriteContext(
                "UPDATE user_account SET phone = ? WHERE id = 1",
                Collections.singletonList(new ParameterMapping.Builder(new Configuration(), "phone", String.class).build()),
                Collections.singletonMap("phone", "13800138000")
        );
        Update update = parseUpdate("UPDATE user_account SET phone = ? WHERE id = 1");

        boolean changed = rewriter.rewrite(update, tableContext, context);

        assertTrue(changed);
        assertTrue(update.toString().contains("`phone_cipher` = ?"));
        assertTrue(update.toString().contains("`phone_hash` = ?"));
        assertTrue(update.toString().contains("`phone_like` = ?"));
        assertEquals(3, context.parameterMappings().size());
    }

    @Test
    void shouldReportChangedForLiteralEncryptedAssignment() throws Exception {
        SqlUpdateSetRewriter rewriter = newUpdateSetRewriter();
        SqlTableContext tableContext = tableContext(sameTableRule());
        SqlRewriteContext context = rewriteContext(
                "UPDATE user_account SET phone = '13800138000' WHERE id = 1",
                Collections.emptyList(),
                Collections.emptyMap()
        );
        Update update = parseUpdate("UPDATE user_account SET phone = '13800138000' WHERE id = 1");

        boolean changed = rewriter.rewrite(update, tableContext, context);

        assertTrue(changed);
        assertTrue(update.toString().contains("`phone_cipher` = '"));
        assertTrue(update.toString().contains("`phone_hash` = '"));
        assertTrue(update.toString().contains("`phone_like` = '"));
    }

    private SqlUpdateSetRewriter newUpdateSetRewriter() {
        EncryptionValueTransformer transformer = new EncryptionValueTransformer(new AlgorithmRegistry(
                Collections.singletonMap("sm4", new Sm4CipherAlgorithm("test-key")),
                Collections.singletonMap("sm3", new Sm3AssistedQueryAlgorithm()),
                Collections.singletonMap("like", new NormalizedLikeQueryAlgorithm())
        ));
        SqlConditionRewriter conditionRewriter = new SqlConditionRewriter(
                transformer,
                this::buildColumn,
                (rule, scenario) -> rule.assistedQueryColumn(),
                (rule, scenario) -> rule.likeQueryColumn(),
                this::quote,
                (select, context, projectionMode) -> {
                }
        );
        SqlWriteExpressionRewriter writeExpressionRewriter = new SqlWriteExpressionRewriter(transformer, conditionRewriter);
        return new SqlUpdateSetRewriter(writeExpressionRewriter, transformer, this::buildColumn);
    }

    private SqlRewriteContext rewriteContext(String sql, List<ParameterMapping> parameterMappings, Object parameterObject) {
        Configuration configuration = new Configuration();
        BoundSql boundSql = new BoundSql(configuration, sql, parameterMappings, parameterObject);
        return new SqlRewriteContext(configuration, boundSql, new ParameterValueResolver());
    }

    private Update parseUpdate(String sql) throws Exception {
        Statement statement = JSqlParserSupport.parseStatement(sql);
        return (Update) statement;
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
