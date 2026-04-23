package io.github.jasper.mybatis.encrypt.core.rewrite;

import java.util.List;
import java.util.Map;

import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.config.SqlDialect;
import io.github.jasper.mybatis.encrypt.core.metadata.AnnotationEncryptMetadataLoader;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import io.github.jasper.mybatis.encrypt.core.metadata.FieldStorageMode;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMap;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.algorithm.support.NormalizedLikeQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.support.Sm3AssistedQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.support.Sm4CipherAlgorithm;
import io.github.jasper.mybatis.encrypt.exception.EncryptionConfigurationException;
import io.github.jasper.mybatis.encrypt.exception.EncryptionErrorCode;
import io.github.jasper.mybatis.encrypt.exception.UnsupportedEncryptedOperationException;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
@Tag("rewrite")
class SqlRewriteEngineTest {

    /**
     * 测试目的：验证写入类 SQL 会把明文字段改写为密文列、辅助查询列、LIKE 列和脱敏列。
     * 测试场景：构造 INSERT/UPDATE/DELETE 语句和 MyBatis 参数绑定，断言改写后的 SQL、生成参数和写入列集合正确。
     */
    @Test
    void shouldRewriteInsertToStorageAndShadowColumns() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        Map<String, Object> parameterObject = Map.of("phone", "13800138000", "name", "Alice");
        BoundSql boundSql = new BoundSql(
                configuration,
                "INSERT INTO user_account (phone, name) VALUES (?, ?)",
                List.of(
                        new ParameterMapping.Builder(configuration, "phone", String.class).build(),
                        new ParameterMapping.Builder(configuration, "name", String.class).build()
                ),
                parameterObject
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.INSERT, Map.class), boundSql);

        assertTrue(result.changed());
        assertTrue(result.sql().contains("`phone_cipher`"));
        assertTrue(result.sql().contains("`phone_hash`"));
        assertTrue(result.sql().contains("`phone_like`"));
        assertTrue(result.sql().contains("`phone_masked`"));
        assertFalse(result.sql().contains("INSERT INTO user_account (`phone`,"));
        result.applyTo(boundSql);
        assertEquals(5, boundSql.getParameterMappings().size());
    }

    /**
     * 测试目的：验证写入类 SQL 会把明文字段改写为密文列、辅助查询列、LIKE 列和脱敏列。
     * 测试场景：构造 INSERT/UPDATE/DELETE 语句和 MyBatis 参数绑定，断言改写后的 SQL、生成参数和写入列集合正确。
     */
    @Test
    void shouldRewriteInsertAcrossStorageModes() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        Map<String, Object> parameterObject = Map.of(
                "id", 1L,
                "name", "Alice",
                "phone", "13800138000",
                "idCard", "320101199001011234"
        );
        BoundSql boundSql = new BoundSql(
                configuration,
                "INSERT INTO user_account (id, name, phone, id_card) VALUES (?, ?, ?, ?)",
                List.of(
                        new ParameterMapping.Builder(configuration, "id", Long.class).build(),
                        new ParameterMapping.Builder(configuration, "name", String.class).build(),
                        new ParameterMapping.Builder(configuration, "phone", String.class).build(),
                        new ParameterMapping.Builder(configuration, "idCard", String.class).build()
                ),
                parameterObject
        );
        boundSql.setAdditionalParameter("idCard", "1001");

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.INSERT, Map.class), boundSql);

        assertTrue(result.changed());
        assertTrue(result.sql().contains("`phone_cipher`"));
        assertTrue(result.sql().contains("`phone_hash`"));
        assertTrue(result.sql().contains("`phone_like`"));
        assertTrue(result.sql().contains("`phone_masked`"));
        assertTrue(result.sql().contains("`id_card`") || result.sql().contains("id_card"));
        result.applyTo(boundSql);
        assertEquals(7, boundSql.getParameterMappings().size());
    }

    /**
     * 测试目的：验证写入类 SQL 会把明文字段改写为密文列、辅助查询列、LIKE 列和脱敏列。
     * 测试场景：构造 INSERT/UPDATE/DELETE 语句和 MyBatis 参数绑定，断言改写后的 SQL、生成参数和写入列集合正确。
     */
    @Test
    void shouldRewriteInsertOnceWhenLikeAndMaskedShareColumn() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = samplePropertiesWithSharedLikeMaskedColumn();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "INSERT INTO user_account (phone, name) VALUES (?, ?)",
                List.of(
                        new ParameterMapping.Builder(configuration, "phone", String.class).build(),
                        new ParameterMapping.Builder(configuration, "name", String.class).build()
                ),
                Map.of("phone", "13800138000", "name", "Alice")
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.INSERT, Map.class), boundSql);

        assertTrue(result.changed());
        assertEquals(1, occurrences(result.sql(), "`phone_like`"));
        assertFalse(result.sql().contains("`phone_masked`"));
        result.applyTo(boundSql);
        assertEquals(4, boundSql.getParameterMappings().size());
    }

    /**
     * 测试目的：验证查询条件中的加密字段会改写为辅助查询列或独立表 EXISTS 谓词。
     * 测试场景：构造等值、LIKE、空值、嵌套括号和子查询条件，断言 SQL 谓词和参数顺序保持正确。
     */
    @Test
    void shouldRewriteSelectColumnToStorageColumnAndEqualityToAssistedColumn() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        Map<String, Object> parameterObject = Map.of("phone", "13800138000");
        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT id, phone FROM user_account WHERE phone = ?",
                List.of(new ParameterMapping.Builder(configuration, "phone", String.class).build()),
                parameterObject
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

        assertTrue(result.changed());
        assertTrue(result.sql().contains("`phone_cipher` AS phone") || result.sql().contains("`phone_cipher` phone"));
        assertTrue(result.sql().contains("`phone_hash` = ?"));
        assertEquals(1, result.maskedParameters().size());
    }

    /**
     * 测试目的：验证 SQL 改写核心组件在当前语句结构下保持安全且确定的改写行为。
     * 测试场景：构造对应 SQL、加密规则和参数上下文，断言 AST 改写结果、参数绑定和安全边界。
     */
    @Test
    void shouldRewriteSqlContainingRepeatedBlankLines() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT id,\n\n\n phone\n\n FROM user_account\n\n WHERE phone = ?",
                List.of(new ParameterMapping.Builder(configuration, "phone", String.class).build()),
                Map.of("phone", "13800138000")
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

        assertTrue(result.changed());
        assertTrue(result.sql().contains("`phone_cipher` AS phone") || result.sql().contains("`phone_cipher` phone"));
        assertTrue(result.sql().contains("`phone_hash` = ?"));
    }

    /**
     * 测试目的：验证 SELECT 投影改写能正确暴露密文列别名并避免重复投影。
     * 测试场景：构造通配符、多表、派生表和 UNION 查询，断言投影列、隐藏辅助列和别名处理符合预期。
     */
    @Test
    void shouldRewriteWildcardSelectByAppendingStorageAlias() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT * FROM user_account WHERE phone = ?",
                List.of(new ParameterMapping.Builder(configuration, "phone", String.class).build()),
                Map.of("phone", "13800138000")
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

        assertTrue(result.changed());
        assertTrue(result.sql().contains("`phone_cipher` AS phone, user_account.*")
                || result.sql().contains("`phone_cipher` phone, user_account.*"));
        assertTrue(result.sql().contains("`phone_hash` = ?"));
    }

    /**
     * 测试目的：验证 SELECT 投影改写能正确暴露密文列别名并避免重复投影。
     * 测试场景：构造通配符、多表、派生表和 UNION 查询，断言投影列、隐藏辅助列和别名处理符合预期。
     */
    @Test
    void shouldRewriteExplicitPlainColumnAndWildcardWithoutAlias() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT id, * FROM user_account WHERE phone = ?",
                List.of(new ParameterMapping.Builder(configuration, "phone", String.class).build()),
                Map.of("phone", "13800138000")
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

        assertTrue(result.changed());
        assertTrue(result.sql().contains("SELECT id, `phone_cipher` AS phone, user_account.* FROM user_account")
                || result.sql().contains("SELECT id, `phone_cipher` phone, user_account.* FROM user_account"));
        assertFalse(result.sql().contains("id, * FROM"));
        assertTrue(result.sql().contains("`phone_hash` = ?"));
    }

    /**
     * 测试目的：验证 SELECT 投影改写能正确暴露密文列别名并避免重复投影。
     * 测试场景：构造通配符、多表、派生表和 UNION 查询，断言投影列、隐藏辅助列和别名处理符合预期。
     */
    @Test
    void shouldRewriteEncryptedColumnAndWildcardWithoutAlias() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT phone, * FROM user_account WHERE phone = ?",
                List.of(new ParameterMapping.Builder(configuration, "phone", String.class).build()),
                Map.of("phone", "13800138000")
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

        assertTrue(result.changed());
        assertEquals(1, occurrences(result.sql(), "`phone_cipher`"));
        assertTrue(result.sql().contains("SELECT `phone_cipher` AS phone, user_account.* FROM user_account")
                || result.sql().contains("SELECT `phone_cipher` phone, user_account.* FROM user_account"));
        assertFalse(result.sql().contains(", * FROM"));
        assertTrue(result.sql().contains("`phone_hash` = ?"));
    }

    /**
     * 测试目的：验证 SELECT 投影改写能正确暴露密文列别名并避免重复投影。
     * 测试场景：构造通配符、多表、派生表和 UNION 查询，断言投影列、隐藏辅助列和别名处理符合预期。
     */
    @Test
    void shouldRewriteExplicitPlainColumnAndWildcardWithTableAlias() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT id, * FROM user_account u WHERE u.phone = ?",
                List.of(new ParameterMapping.Builder(configuration, "phone", String.class).build()),
                Map.of("phone", "13800138000")
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

        assertTrue(result.changed());
        assertTrue(result.sql().contains("SELECT id, u.`phone_cipher` AS phone, u.* FROM user_account u")
                || result.sql().contains("SELECT id, u.`phone_cipher` phone, u.* FROM user_account u"));
        assertFalse(result.sql().contains("id, * FROM"));
        assertTrue(result.sql().contains("u.`phone_hash` = ?"));
    }

    /**
     * 测试目的：验证 SELECT 投影改写能正确暴露密文列别名并避免重复投影。
     * 测试场景：构造通配符、多表、派生表和 UNION 查询，断言投影列、隐藏辅助列和别名处理符合预期。
     */
    @Test
    void shouldRewriteEncryptedColumnAndWildcardWithTableAlias() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT u.phone, * FROM user_account u WHERE u.phone = ?",
                List.of(new ParameterMapping.Builder(configuration, "phone", String.class).build()),
                Map.of("phone", "13800138000")
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

        assertTrue(result.changed());
        assertEquals(1, occurrences(result.sql(), "`phone_cipher`"));
        assertTrue(result.sql().contains("SELECT u.`phone_cipher` AS phone, u.* FROM user_account u")
                || result.sql().contains("SELECT u.`phone_cipher` phone, u.* FROM user_account u"));
        assertFalse(result.sql().contains(", * FROM"));
        assertTrue(result.sql().contains("u.`phone_hash` = ?"));
    }

    /**
     * 测试目的：验证 SELECT 投影改写能正确暴露密文列别名并避免重复投影。
     * 测试场景：构造通配符、多表、派生表和 UNION 查询，断言投影列、隐藏辅助列和别名处理符合预期。
     */
    @Test
    void shouldKeepWildcardSelectWhenStorageColumnEqualsLogicalColumn() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = samplePropertiesWithInlineStorageColumn();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT * FROM user_account t WHERE phone = ?",
                List.of(new ParameterMapping.Builder(configuration, "phone", String.class).build()),
                Map.of("phone", "13800138000")
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

        assertTrue(result.changed());
        assertTrue(result.sql().startsWith("SELECT * FROM user_account"));
        assertFalse(result.sql().contains(", *"));
        assertFalse(result.sql().contains("user_account.*"));
        assertTrue(result.sql().contains("`phone_hash` = ?"));
    }

    /**
     * 测试目的：验证 SELECT 投影改写能正确暴露密文列别名并避免重复投影。
     * 测试场景：构造通配符、多表、派生表和 UNION 查询，断言投影列、隐藏辅助列和别名处理符合预期。
     */
    @Test
    void shouldAvoidDuplicatedEncryptedProjectionWhenSelectContainsExplicitColumnAndWildcard() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT * FROM user_account WHERE phone = ?",
                List.of(new ParameterMapping.Builder(configuration, "phone", String.class).build()),
                Map.of("phone", "13800138000")
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

        assertTrue(result.changed());
        assertEquals(1, occurrences(result.sql(), "`phone_cipher`"));
        assertTrue(result.sql().contains("`phone_cipher` AS phone, user_account.*")
                || result.sql().contains("`phone_cipher` phone, user_account.*"));
        assertTrue(result.sql().contains("`phone_hash` = ?"));
    }

    /**
     * 测试目的：验证 SELECT 投影改写能正确暴露密文列别名并避免重复投影。
     * 测试场景：构造通配符、多表、派生表和 UNION 查询，断言投影列、隐藏辅助列和别名处理符合预期。
     */
    @Test
    void shouldRewriteAliasedWildcardByPrependingEncryptedAliasBeforeTableWildcard() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT u.*, o.id FROM user_account u JOIN order_account o ON u.id = o.user_id WHERE u.phone = ?",
                List.of(new ParameterMapping.Builder(configuration, "phone", String.class).build()),
                Map.of("phone", "13800138000")
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

        assertTrue(result.changed());
        assertTrue(result.sql().contains("u.`phone_cipher` AS phone, u.*, o.id")
                || result.sql().contains("u.`phone_cipher` phone, u.*, o.id"));
        assertFalse(result.sql().contains("u.*, u.`phone_cipher` AS phone"));
        assertTrue(result.sql().contains("u.`phone_hash` = ?"));
    }

    /**
     * 测试目的：验证 SELECT 投影改写能正确暴露密文列别名并避免重复投影。
     * 测试场景：构造通配符、多表、派生表和 UNION 查询，断言投影列、隐藏辅助列和别名处理符合预期。
     */
    @Test
    void shouldRewriteMultiTableSelectUsingExplicitTableWildcardWithoutAlias() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT user_account.phone, user_account.*, order_account.* " +
                        "FROM user_account JOIN order_account ON user_account.id = order_account.user_id " +
                        "WHERE user_account.phone = ?",
                List.of(new ParameterMapping.Builder(configuration, "phone", String.class).build()),
                Map.of("phone", "13800138000")
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

        assertTrue(result.changed());
        assertEquals(1, occurrences(result.sql(), "`phone_cipher`"));
        assertTrue(result.sql().contains("user_account.`phone_cipher` AS phone, user_account.*, order_account.*")
                || result.sql().contains("user_account.`phone_cipher` phone, user_account.*, order_account.*"));
        assertFalse(result.sql().contains("user_account.*, user_account.`phone_cipher` AS phone"));
        assertTrue(result.sql().contains("user_account.`phone_hash` = ?"));
    }

    /**
     * 测试目的：验证 SELECT 投影改写能正确暴露密文列别名并避免重复投影。
     * 测试场景：构造通配符、多表、派生表和 UNION 查询，断言投影列、隐藏辅助列和别名处理符合预期。
     */
    @Test
    void shouldRewriteMultiTableSelectUsingExplicitTableWildcardWithAlias() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT u.phone, u.*, o.* FROM user_account u JOIN order_account o ON u.id = o.user_id WHERE u.phone = ?",
                List.of(new ParameterMapping.Builder(configuration, "phone", String.class).build()),
                Map.of("phone", "13800138000")
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

        assertTrue(result.changed());
        assertEquals(1, occurrences(result.sql(), "`phone_cipher`"));
        assertTrue(result.sql().contains("u.`phone_cipher` AS phone, u.*, o.*")
                || result.sql().contains("u.`phone_cipher` phone, u.*, o.*"));
        assertFalse(result.sql().contains("u.*, u.`phone_cipher` AS phone"));
        assertTrue(result.sql().contains("u.`phone_hash` = ?"));
    }

    /**
     * 测试目的：验证不支持或高风险的加密字段 SQL 会按安全策略快速失败。
     * 测试场景：构造 ORDER BY、聚合、范围条件、歧义列或非法操作数等 SQL，断言异常类型和错误码符合约束。
     */
    @Test
    void shouldFailFastForBareWildcardMixedWithEncryptedProjectionInJoin() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT u.phone, * FROM user_account u JOIN order_account o ON u.id = o.user_id WHERE u.phone = ?",
                List.of(new ParameterMapping.Builder(configuration, "phone", String.class).build()),
                Map.of("phone", "13800138000")
        );

        UnsupportedEncryptedOperationException exception = assertThrows(
                UnsupportedEncryptedOperationException.class,
                () -> engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql)
        );

        assertEquals(EncryptionErrorCode.UNSUPPORTED_ENCRYPTED_SELECT, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("table.*") || exception.getMessage().contains("alias.*"));
    }

    /**
     * 测试目的：验证不支持或高风险的加密字段 SQL 会按安全策略快速失败。
     * 测试场景：构造 ORDER BY、聚合、范围条件、歧义列或非法操作数等 SQL，断言异常类型和错误码符合约束。
     */
    @Test
    void shouldFailFastForBareWildcardOnlyInJoinWithEncryptedTableRule() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT * FROM user_account u JOIN order_account o ON u.id = o.user_id WHERE u.phone = ?",
                List.of(new ParameterMapping.Builder(configuration, "phone", String.class).build()),
                Map.of("phone", "13800138000")
        );

        UnsupportedEncryptedOperationException exception = assertThrows(
                UnsupportedEncryptedOperationException.class,
                () -> engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql)
        );

        assertEquals(EncryptionErrorCode.UNSUPPORTED_ENCRYPTED_SELECT, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("table.*") || exception.getMessage().contains("alias.*"));
    }

    /**
     * 测试目的：验证不支持或高风险的加密字段 SQL 会按安全策略快速失败。
     * 测试场景：构造 ORDER BY、聚合、范围条件、歧义列或非法操作数等 SQL，断言异常类型和错误码符合约束。
     */
    @Test
    void shouldFailFastForBareWildcardBeforeEncryptedProjectionInJoin() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT *, u.phone FROM user_account u JOIN order_account o ON u.id = o.user_id WHERE u.phone = ?",
                List.of(new ParameterMapping.Builder(configuration, "phone", String.class).build()),
                Map.of("phone", "13800138000")
        );

        UnsupportedEncryptedOperationException exception = assertThrows(
                UnsupportedEncryptedOperationException.class,
                () -> engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql)
        );

        assertEquals(EncryptionErrorCode.UNSUPPORTED_ENCRYPTED_SELECT, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("table.*") || exception.getMessage().contains("alias.*"));
    }

    /**
     * 测试目的：验证查询条件中的加密字段会改写为辅助查询列或独立表 EXISTS 谓词。
     * 测试场景：构造等值、LIKE、空值、嵌套括号和子查询条件，断言 SQL 谓词和参数顺序保持正确。
     */
    @Test
    void shouldRewriteJoinAndNestedWhereConditions() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT u.id, u.phone, o.id FROM user_account u JOIN order_account o ON u.id = o.user_id " +
                        "WHERE (u.phone = ? OR u.phone LIKE ?)",
                List.of(
                        new ParameterMapping.Builder(configuration, "phone", String.class).build(),
                        new ParameterMapping.Builder(configuration, "phoneLike", String.class).build()
                ),
                Map.of("phone", "13800138000", "phoneLike", "%1380%")
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

        assertTrue(result.changed());
        assertTrue(result.sql().contains("u.`phone_cipher` AS phone") || result.sql().contains("u.`phone_cipher` phone"));
        assertTrue(result.sql().contains("u.`phone_hash` = ?"));
        assertTrue(result.sql().contains("u.`phone_like` LIKE ?"));
    }

    /**
     * 测试目的：验证查询条件中的加密字段会改写为辅助查询列或独立表 EXISTS 谓词。
     * 测试场景：构造等值、LIKE、空值、嵌套括号和子查询条件，断言 SQL 谓词和参数顺序保持正确。
     */
    @Test
    void shouldRewriteSameTableLikeConcatPattern() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT u.id FROM user_account u WHERE u.status = ? AND u.phone LIKE CONCAT('%', ?, '%')",
                List.of(
                        new ParameterMapping.Builder(configuration, "status", Integer.class).build(),
                        new ParameterMapping.Builder(configuration, "phoneSegment", String.class).build()
                ),
                Map.of("status", 1, "phoneSegment", "AbC")
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

        assertTrue(result.changed());
        assertTrue(result.sql().contains("u.`phone_like` LIKE ?") || result.sql().contains("u.phone_like LIKE ?"));
        assertTrue(result.sql().contains("u.`phone_hash` = ?") || result.sql().contains("u.phone_hash = ?"));
        assertTrue(result.sql().contains(" OR "));
        assertFalse(result.sql().contains("CONCAT("));
        result.applyTo(boundSql);
        assertEquals(3, boundSql.getParameterMappings().size());
        assertEquals("status", boundSql.getParameterMappings().get(0).getProperty());
        assertTrue(boundSql.getParameterMappings().get(1).getProperty().startsWith("__encrypt_generated_"));
        assertTrue(boundSql.getParameterMappings().get(2).getProperty().startsWith("__encrypt_generated_"));
        assertEquals("%abc%", boundSql.getAdditionalParameter(boundSql.getParameterMappings().get(1).getProperty()));
        assertEquals(new Sm3AssistedQueryAlgorithm().transform("AbC"),
                boundSql.getAdditionalParameter(boundSql.getParameterMappings().get(2).getProperty()));
        assertEquals(2, result.maskedParameters().size());
    }

    /**
     * 测试目的：验证查询条件中的加密字段会改写为辅助查询列或独立表 EXISTS 谓词。
     * 测试场景：构造等值、LIKE、空值、嵌套括号和子查询条件，断言 SQL 谓词和参数顺序保持正确。
     */
    @Test
    void shouldRewriteExactLikeToLikeAndAssistedFallback() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT u.id FROM user_account u WHERE u.phone LIKE ?",
                List.of(new ParameterMapping.Builder(configuration, "phoneLike", String.class).build()),
                Map.of("phoneLike", "13800138000")
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

        assertTrue(result.changed());
        assertTrue(result.sql().contains("u.`phone_hash` = ?") || result.sql().contains("u.phone_hash = ?"));
        assertTrue(result.sql().contains("u.`phone_like` LIKE ?") || result.sql().contains("u.phone_like LIKE ?"));
        assertTrue(result.sql().contains(" OR "));
        result.applyTo(boundSql);
        assertEquals(2, boundSql.getParameterMappings().size());
        assertTrue(boundSql.getParameterMappings().get(0).getProperty().startsWith("__encrypt_generated_"));
        assertTrue(boundSql.getParameterMappings().get(1).getProperty().startsWith("__encrypt_generated_"));
        assertEquals("13800138000", boundSql.getAdditionalParameter(boundSql.getParameterMappings().get(0).getProperty()));
        assertEquals(new Sm3AssistedQueryAlgorithm().transform("13800138000"),
                boundSql.getAdditionalParameter(boundSql.getParameterMappings().get(1).getProperty()));
        assertEquals(2, result.maskedParameters().size());
    }

    /**
     * 测试目的：验证不支持或高风险的加密字段 SQL 会按安全策略快速失败。
     * 测试场景：构造 ORDER BY、聚合、范围条件、歧义列或非法操作数等 SQL，断言异常类型和错误码符合约束。
     */
    @Test
    void shouldFailEqualityQueryWithoutAssistedQueryColumn() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = samplePropertiesWithoutPhoneAssistedQueryColumn();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT id, phone FROM user_account WHERE phone = ?",
                List.of(new ParameterMapping.Builder(configuration, "phone", String.class).build()),
                Map.of("phone", "13800138000")
        );

        UnsupportedEncryptedOperationException ex = assertThrows(
                UnsupportedEncryptedOperationException.class,
                () -> engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql)
        );

        assertEquals(EncryptionErrorCode.MISSING_ASSISTED_QUERY_COLUMN, ex.getErrorCode());
        assertEquals(
                "Encrypted equality query requires assistedQueryColumn. property=phone, table=user_account, column=phone",
                ex.getMessage()
        );
    }

    /**
     * 测试目的：验证不支持或高风险的加密字段 SQL 会按安全策略快速失败。
     * 测试场景：构造 ORDER BY、聚合、范围条件、歧义列或非法操作数等 SQL，断言异常类型和错误码符合约束。
     */
    @Test
    void shouldFailLikeQueryWithoutLikeQueryColumn() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = samplePropertiesWithoutPhoneLikeQueryColumn();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT id, phone FROM user_account WHERE phone LIKE ?",
                List.of(new ParameterMapping.Builder(configuration, "phoneLike", String.class).build()),
                Map.of("phoneLike", "%1380%")
        );

        UnsupportedEncryptedOperationException ex = assertThrows(
                UnsupportedEncryptedOperationException.class,
                () -> engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql)
        );

        assertEquals(EncryptionErrorCode.MISSING_LIKE_QUERY_COLUMN, ex.getErrorCode());
        assertEquals(
                "Encrypted LIKE query requires likeQueryColumn. property=phone, table=user_account, column=phone",
                ex.getMessage()
        );
    }

    /**
     * 测试目的：验证不支持或高风险的加密字段 SQL 会按安全策略快速失败。
     * 测试场景：构造 ORDER BY、聚合、范围条件、歧义列或非法操作数等 SQL，断言异常类型和错误码符合约束。
     */
    @Test
    void shouldFailInsertSelectWithStructuredErrorCode() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "INSERT INTO user_account (phone) SELECT phone FROM user_account",
                List.of(),
                Map.of()
        );

        UnsupportedEncryptedOperationException exception = assertThrows(
                UnsupportedEncryptedOperationException.class,
                () -> engine.rewrite(mappedStatement(configuration, SqlCommandType.INSERT, Map.class), boundSql)
        );

        assertEquals(EncryptionErrorCode.UNSUPPORTED_ENCRYPTED_INSERT, exception.getErrorCode());
        assertEquals("Only VALUES inserts are supported for encrypted tables.", exception.getMessage());
    }

    /**
     * 测试目的：验证不支持或高风险的加密字段 SQL 会按安全策略快速失败。
     * 测试场景：构造 ORDER BY、聚合、范围条件、歧义列或非法操作数等 SQL，断言异常类型和错误码符合约束。
     */
    @Test
    void shouldFailSeparateTableEqualityQueryWithoutAssistedQueryColumn() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = samplePropertiesWithoutSeparateTableAssistedQueryColumn();
        EncryptionConfigurationException ex = assertThrows(
                EncryptionConfigurationException.class,
                () -> new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader())
        );

        assertEquals(EncryptionErrorCode.MISSING_ASSISTED_QUERY_COLUMN, ex.getErrorCode());
        assertEquals(
                "Separate-table encrypted field must define assistedQueryColumn. property=idCard, table=user_account, column=id_card, storageTable=user_id_card_encrypt",
                ex.getMessage()
        );
    }

    /**
     * 测试目的：验证不支持或高风险的加密字段 SQL 会按安全策略快速失败。
     * 测试场景：构造 ORDER BY、聚合、范围条件、歧义列或非法操作数等 SQL，断言异常类型和错误码符合约束。
     */
    @Test
    void shouldExposeStructuredRewriteFailureWhenParserCannotHandleSql() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT FROM user_account WHERE phone = ?",
                List.of(new ParameterMapping.Builder(configuration, "phone", String.class).build()),
                Map.of("phone", "13800138000")
        );

        EncryptionConfigurationException exception = assertThrows(
                EncryptionConfigurationException.class,
                () -> engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql)
        );

        assertEquals(EncryptionErrorCode.SQL_REWRITE_FAILED, exception.getErrorCode());
        assertTrue(exception.getMessage().startsWith("Failed to rewrite encrypted SQL:"));
    }

    /**
     * 测试目的：验证 SELECT 投影改写能正确暴露密文列别名并避免重复投影。
     * 测试场景：构造通配符、多表、派生表和 UNION 查询，断言投影列、隐藏辅助列和别名处理符合预期。
     */
    @Test
    void shouldRewriteEncryptedProjectionInComplexDerivedJoinQuery() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT a.is_edit, a.gridman, c.id, c.name, c.phone FROM (" +
                        "SELECT o.user_id, coalesce(max(CASE WHEN o.user_id = ? THEN o.created_at END), min(o.created_at)) AS wx_createtime, " +
                        "max(o.user_id = ?) AS is_edit, GROUP_CONCAT(o.remark) AS remark, GROUP_CONCAT(o.owner_name) AS gridman, " +
                        "GROUP_CONCAT(o.user_id) AS userid FROM order_account o WHERE o.deleted = 0 AND o.user_id IS NOT NULL " +
                        "GROUP BY o.user_id) a JOIN user_account c ON a.user_id = c.id " +
                        "WHERE (a.userid REGEXP ?) ORDER BY a.is_edit DESC, a.wx_createtime DESC, a.user_id",
                List.of(
                        new ParameterMapping.Builder(configuration, "userId1", Long.class).build(),
                        new ParameterMapping.Builder(configuration, "userId2", Long.class).build(),
                        new ParameterMapping.Builder(configuration, "regexp", String.class).build()
                ),
                Map.of("userId1", 1L, "userId2", 1L, "regexp", "1")
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

        assertTrue(result.changed());
        assertTrue(result.sql().contains("c.`phone_cipher` AS phone") || result.sql().contains("c.`phone_cipher` phone"));
        assertTrue(result.sql().contains("a.userid REGEXP ?"));
        assertTrue(result.sql().contains("ORDER BY a.is_edit DESC, a.wx_createtime DESC, a.user_id"));
        assertFalse(result.sql().contains("GROUP_CONCAT(c.`phone_cipher`)"));
        assertEquals(0, result.maskedParameters().size());
    }

    /**
     * 测试目的：验证查询条件中的加密字段会改写为辅助查询列或独立表 EXISTS 谓词。
     * 测试场景：构造等值、LIKE、空值、嵌套括号和子查询条件，断言 SQL 谓词和参数顺序保持正确。
     */
    @Test
    void shouldRewriteExistsSubquery() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT o.id FROM order_account o WHERE EXISTS (" +
                        "SELECT 1 FROM user_account u WHERE u.id = o.user_id AND u.phone = ?)",
                List.of(new ParameterMapping.Builder(configuration, "phone", String.class).build()),
                Map.of("phone", "13800138000")
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

        assertTrue(result.changed());
        assertTrue(result.sql().contains("u.`phone_hash` = ?"));
    }

    /**
     * 测试目的：验证 SELECT 投影改写能正确暴露密文列别名并避免重复投影。
     * 测试场景：构造通配符、多表、派生表和 UNION 查询，断言投影列、隐藏辅助列和别名处理符合预期。
     */
    @Test
    void shouldRewriteUnionBranches() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT phone FROM user_account WHERE phone = ? UNION ALL " +
                        "SELECT phone FROM user_archive WHERE phone = ?",
                List.of(
                        new ParameterMapping.Builder(configuration, "phone1", String.class).build(),
                        new ParameterMapping.Builder(configuration, "phone2", String.class).build()
                ),
                Map.of("phone1", "13800138000", "phone2", "13900139000")
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

        assertTrue(result.changed());
        assertTrue(result.sql().contains("`phone_cipher` AS phone") || result.sql().contains("`phone_cipher` phone"));
        assertTrue(result.sql().contains("`archive_phone_cipher` AS phone") || result.sql().contains("`archive_phone_cipher` phone"));
        assertTrue(result.sql().contains("`phone_hash` = ?"));
        assertTrue(result.sql().contains("`archive_phone_hash` = ?"));
    }

    /**
     * 测试目的：验证 SELECT 投影改写能正确暴露密文列别名并避免重复投影。
     * 测试场景：构造通配符、多表、派生表和 UNION 查询，断言投影列、隐藏辅助列和别名处理符合预期。
     */
    @Test
    void shouldRewriteMultiUnionWithNestedSubqueriesAcrossStorageModes() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT u.id FROM user_account u WHERE u.phone = ? AND EXISTS (" +
                        "SELECT 1 FROM order_account o WHERE o.user_id = u.id AND o.id IN (" +
                        "SELECT p.order_id FROM order_participant p WHERE p.seq_no = 1)) " +
                        "UNION " +
                        "SELECT u.id FROM user_account u WHERE u.id_card = ? AND u.id IN (" +
                        "SELECT o.related_user_id FROM order_account o WHERE o.related_user_id IS NOT NULL AND o.id IN (" +
                        "SELECT p.order_id FROM order_participant p WHERE p.seq_no = 2)) " +
                        "UNION " +
                        "SELECT u.id FROM user_account u WHERE u.phone LIKE ? AND u.id_card LIKE ? AND EXISTS (" +
                        "SELECT 1 FROM order_account o WHERE o.user_id = u.id AND EXISTS (" +
                        "SELECT 1 FROM order_participant p WHERE p.order_id = o.id AND p.user_id = u.id))",
                List.of(
                        new ParameterMapping.Builder(configuration, "phone", String.class).build(),
                        new ParameterMapping.Builder(configuration, "idCard", String.class).build(),
                        new ParameterMapping.Builder(configuration, "phoneLike", String.class).build(),
                        new ParameterMapping.Builder(configuration, "idCardLike", String.class).build()
                ),
                Map.of(
                        "phone", "13800138000",
                        "idCard", "320101199001011234",
                        "phoneLike", "%1380%",
                        "idCardLike", "%320101%"
                )
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

        assertTrue(result.changed());
        assertTrue(result.sql().contains("UNION"));
        assertTrue(result.sql().contains("u.`phone_hash` = ?") || result.sql().contains("u.phone_hash = ?"));
        assertTrue(result.sql().contains("u.`phone_like` LIKE ?") || result.sql().contains("u.phone_like LIKE ?"));
        assertTrue(result.sql().contains("`id_card_hash` = ?") || result.sql().contains("id_card_hash = ?"));
        assertTrue(result.sql().contains("`id_card_like` LIKE ?") || result.sql().contains("id_card_like LIKE ?"));
        assertTrue(result.sql().contains("`user_id_card_encrypt`") || result.sql().contains("user_id_card_encrypt"));
        assertEquals(6, result.maskedParameters().size());
    }

    /**
     * 测试目的：验证 SELECT 投影改写能正确暴露密文列别名并避免重复投影。
     * 测试场景：构造通配符、多表、派生表和 UNION 查询，断言投影列、隐藏辅助列和别名处理符合预期。
     */
    @Test
    void shouldRewriteWrappedPermissionUnionWithNestedSubqueriesWithoutTypeCastError() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        DatabaseEncryptionProperties.TableRuleProperties permissionTableRule =
                new DatabaseEncryptionProperties.TableRuleProperties();
        permissionTableRule.setTable("sys_permission");

        DatabaseEncryptionProperties.FieldRuleProperties urlFieldRule =
                new DatabaseEncryptionProperties.FieldRuleProperties();
        urlFieldRule.setProperty("url");
        urlFieldRule.setColumn("url");
        urlFieldRule.setStorageColumn("url_cipher");
        urlFieldRule.setAssistedQueryColumn("url_hash");
        urlFieldRule.setLikeQueryColumn("url_like");
        permissionTableRule.getFields().add(urlFieldRule);
        properties.getTables().add(permissionTableRule);

        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT h.id, h.parent_id, h.name, h.url, h.component, h.is_route, h.component_name, h.redirect, " +
                        "h.menu_type, h.perms, h.perms_type, h.sort_no, h.always_show, h.icon, h.is_leaf, h.keep_alive, " +
                        "h.hidden, h.hide_tab, h.rule_flag, h.status, h.internal_or_external " +
                        "FROM (" +
                        "SELECT p.id, p.parent_id, p.name, p.url, p.component, p.is_route, p.component_name, p.redirect, " +
                        "p.menu_type, p.perms, p.perms_type, p.sort_no, p.always_show, p.icon, p.is_leaf, p.keep_alive, " +
                        "p.hidden, p.hide_tab, p.rule_flag, p.status, p.internal_or_external " +
                        "FROM sys_permission p " +
                        "WHERE p.del_flag = 0 AND (p.id IN (" +
                        "SELECT DISTINCT a.permission_id FROM sys_role_permission a " +
                        "JOIN sys_role b ON a.role_id = b.id " +
                        "JOIN sys_user_role c ON c.role_id = b.id AND c.user_id = ?) " +
                        "OR (p.url LIKE '%:code' AND p.url LIKE '/online%' AND p.hidden = 1) " +
                        "OR (p.url LIKE '%:id' AND p.url LIKE '/online%' AND p.hidden = 1) " +
                        "OR p.url = '/online') " +
                        "UNION " +
                        "SELECT p.id, p.parent_id, p.name, p.url, p.component, p.is_route, p.component_name, p.redirect, " +
                        "p.menu_type, p.perms, p.perms_type, p.sort_no, p.always_show, p.icon, p.is_leaf, p.keep_alive, " +
                        "p.hidden, p.hide_tab, p.rule_flag, p.status, p.internal_or_external " +
                        "FROM sys_permission p " +
                        "WHERE p.id IN (" +
                        "SELECT DISTINCT a.permission_id FROM sys_depart_role_permission a " +
                        "INNER JOIN sys_depart_role b ON a.role_id = b.id " +
                        "INNER JOIN sys_depart_role_user c ON c.drole_id = b.id AND c.user_id = ?) " +
                        "AND p.del_flag = 0 " +
                        "UNION " +
                        "SELECT p.id, p.parent_id, p.name, p.url, p.component, p.is_route, p.component_name, p.redirect, " +
                        "p.menu_type, p.perms, p.perms_type, p.sort_no, p.always_show, p.icon, p.is_leaf, p.keep_alive, " +
                        "p.hidden, p.hide_tab, p.rule_flag, p.status, p.internal_or_external " +
                        "FROM sys_permission p " +
                        "WHERE p.id IN (" +
                        "SELECT DISTINCT a.permission_id FROM sys_tenant_pack_perms a " +
                        "INNER JOIN sys_tenant_pack b ON a.pack_id = b.id AND b.status = '1' " +
                        "INNER JOIN sys_tenant st ON st.id = b.tenant_id AND st.del_flag = 0 " +
                        "INNER JOIN sys_tenant_pack_user c ON c.pack_id = b.id AND c.status = '1' AND c.user_id = ?) " +
                        "AND p.del_flag = 0" +
                        ") h ORDER BY h.sort_no ASC",
                List.of(
                        new ParameterMapping.Builder(configuration, "userId1", String.class).build(),
                        new ParameterMapping.Builder(configuration, "userId2", String.class).build(),
                        new ParameterMapping.Builder(configuration, "userId3", String.class).build()
                ),
                Map.of("userId1", "u-1", "userId2", "u-1", "userId3", "u-1")
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

        assertTrue(result.changed());
        assertTrue(result.sql().contains("UNION"));
        assertTrue(result.sql().contains("`url_cipher` AS url") || result.sql().contains("`url_cipher` url"));
        assertTrue(result.sql().contains("`url_like` LIKE") || result.sql().contains("url_like LIKE"));
        assertTrue(result.sql().contains("`url_hash` =") || result.sql().contains("url_hash ="));
        assertTrue(result.sql().contains("ORDER BY h.sort_no ASC"));
    }

    /**
     * 测试目的：验证 SQL 改写核心组件在当前语句结构下保持安全且确定的改写行为。
     * 测试场景：构造对应 SQL、加密规则和参数上下文，断言 AST 改写结果、参数绑定和安全边界。
     */
    @Test
    void shouldRewriteEncryptedInSubquery() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT phone FROM user_account WHERE phone IN (" +
                        "SELECT phone FROM user_archive WHERE phone LIKE ?)",
                List.of(new ParameterMapping.Builder(configuration, "phoneLike", String.class).build()),
                Map.of("phoneLike", "%1380%")
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

        assertTrue(result.changed());
        assertTrue(result.sql().contains("`phone_hash` IN (SELECT `archive_phone_hash` AS phone FROM user_archive")
                || result.sql().contains("`phone_hash` IN (SELECT `archive_phone_hash` phone FROM user_archive"));
        assertTrue(result.sql().contains("`archive_phone_like` LIKE ?"));
    }

    /**
     * 测试目的：验证查询条件中的加密字段会改写为辅助查询列或独立表 EXISTS 谓词。
     * 测试场景：构造等值、LIKE、空值、嵌套括号和子查询条件，断言 SQL 谓词和参数顺序保持正确。
     */
    @Test
    void shouldRewriteDerivedTableEqualityPredicate() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT t.phone FROM (SELECT phone FROM user_account) t WHERE t.phone = ?",
                List.of(new ParameterMapping.Builder(configuration, "phone", String.class).build()),
                Map.of("phone", "13800138000")
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

        assertTrue(result.changed());
        assertTrue(result.sql().contains("(SELECT `phone_cipher` phone"));
        assertTrue(result.sql().contains("__enc_assisted_phone"));
        assertTrue(result.sql().contains("WHERE t.`__enc_assisted_phone` = ?") || result.sql().contains("WHERE t.__enc_assisted_phone = ?"));
        assertEquals(1, result.maskedParameters().size());
    }

    /**
     * 测试目的：验证查询条件中的加密字段会改写为辅助查询列或独立表 EXISTS 谓词。
     * 测试场景：构造等值、LIKE、空值、嵌套括号和子查询条件，断言 SQL 谓词和参数顺序保持正确。
     */
    @Test
    void shouldRewriteDerivedTableLikePredicate() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT t.phone FROM (SELECT phone FROM user_account) t WHERE t.phone LIKE ?",
                List.of(new ParameterMapping.Builder(configuration, "phoneLike", String.class).build()),
                Map.of("phoneLike", "%1380%")
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

        assertTrue(result.changed());
        assertTrue(result.sql().contains("__enc_like_phone"));
        assertTrue(result.sql().contains("__enc_assisted_phone"));
        assertTrue(result.sql().contains("LIKE ?"));
        assertTrue(result.sql().contains("= ?"));
        assertTrue(result.sql().contains(" OR "));
        assertEquals(2, result.maskedParameters().size());
    }

    /**
     * 测试目的：验证查询条件中的加密字段会改写为辅助查询列或独立表 EXISTS 谓词。
     * 测试场景：构造等值、LIKE、空值、嵌套括号和子查询条件，断言 SQL 谓词和参数顺序保持正确。
     */
    @Test
    void shouldRewriteNotInList() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT phone FROM user_account WHERE phone NOT IN (?, ?)",
                List.of(
                        new ParameterMapping.Builder(configuration, "phone1", String.class).build(),
                        new ParameterMapping.Builder(configuration, "phone2", String.class).build()
                ),
                Map.of("phone1", "13800138000", "phone2", "13900139000")
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

        assertTrue(result.changed());
        assertTrue(result.sql().contains("`phone_hash` NOT IN (?, ?)"));
        assertEquals(2, result.maskedParameters().size());
    }

    /**
     * 测试目的：验证查询条件中的加密字段会改写为辅助查询列或独立表 EXISTS 谓词。
     * 测试场景：构造等值、LIKE、空值、嵌套括号和子查询条件，断言 SQL 谓词和参数顺序保持正确。
     */
    @Test
    void shouldRewriteNotWrappedEquality() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT phone FROM user_account WHERE NOT (phone = ?)",
                List.of(new ParameterMapping.Builder(configuration, "phone", String.class).build()),
                Map.of("phone", "13800138000")
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

        assertTrue(result.changed());
        assertTrue(result.sql().contains("NOT"));
        assertTrue(result.sql().contains("`phone_hash` = ?"));
    }

    /**
     * 测试目的：验证查询条件中的加密字段会改写为辅助查询列或独立表 EXISTS 谓词。
     * 测试场景：构造等值、LIKE、空值、嵌套括号和子查询条件，断言 SQL 谓词和参数顺序保持正确。
     */
    @Test
    void shouldRewriteIsNullToStorageColumn() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT phone FROM user_account WHERE phone IS NULL",
                List.of(),
                Map.of()
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

        assertTrue(result.changed());
        assertTrue(result.sql().contains("`phone_cipher` IS NULL"));
    }

    /**
     * 测试目的：验证查询条件中的加密字段会改写为辅助查询列或独立表 EXISTS 谓词。
     * 测试场景：构造等值、LIKE、空值、嵌套括号和子查询条件，断言 SQL 谓词和参数顺序保持正确。
     */
    @Test
    void shouldRewriteSeparateTableEqualityUsingReferencedStorageId() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT u.id FROM user_account u WHERE u.id_card = ?",
                List.of(new ParameterMapping.Builder(configuration, "idCard", String.class).build()),
                Map.of("idCard", "320101199001011234")
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);
        System.out.println("EQUALITY SQL => " + result.sql());

        assertTrue(result.changed());
        assertTrue(result.sql().contains("EXISTS"));
        assertTrue(result.sql().contains("`user_id_card_encrypt`"));
        assertTrue(result.sql().contains("`id_card_hash` = u.`id_card`") || result.sql().contains("`id_card_hash` = `id_card`")
                || result.sql().contains("id_card_hash = u.id_card") || result.sql().contains("id_card_hash = id_card"));
        assertTrue(result.sql().contains("`id_card_hash` = ?") || result.sql().contains("id_card_hash = ?"));
    }

    /**
     * 测试目的：验证查询条件中的加密字段会改写为辅助查询列或独立表 EXISTS 谓词。
     * 测试场景：构造等值、LIKE、空值、嵌套括号和子查询条件，断言 SQL 谓词和参数顺序保持正确。
     */
    @Test
    void shouldRewriteSeparateTableLikeUsingReferencedStorageId() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT u.id FROM user_account u WHERE u.id_card LIKE ?",
                List.of(new ParameterMapping.Builder(configuration, "idCardLike", String.class).build()),
                Map.of("idCardLike", "%320101%")
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

        assertTrue(result.changed());
        assertTrue(result.sql().contains("EXISTS"));
        assertTrue(result.sql().contains("`user_id_card_encrypt`"));
        assertTrue(result.sql().contains("`id_card_hash` = u.`id_card`") || result.sql().contains("`id_card_hash` = `id_card`")
                || result.sql().contains("id_card_hash = u.id_card") || result.sql().contains("id_card_hash = id_card"));
        assertTrue(result.sql().contains("`id_card_like` LIKE ?") || result.sql().contains("id_card_like LIKE ?"));
        assertEquals(2, result.maskedParameters().size());
    }

    /**
     * 测试目的：验证查询条件中的加密字段会改写为辅助查询列或独立表 EXISTS 谓词。
     * 测试场景：构造等值、LIKE、空值、嵌套括号和子查询条件，断言 SQL 谓词和参数顺序保持正确。
     */
    @Test
    void shouldRewriteSeparateTableExactLikeToHashEqualityExistsSubQuery() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT u.id FROM user_account u WHERE u.id_card LIKE ?",
                List.of(new ParameterMapping.Builder(configuration, "idCardLike", String.class).build()),
                Map.of("idCardLike", "320101199001011234")
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

        assertTrue(result.changed());
        assertTrue(result.sql().contains("EXISTS"));
        assertTrue(result.sql().contains("`user_id_card_encrypt`"));
        assertTrue(result.sql().contains("`id_card_hash` = ?") || result.sql().contains("id_card_hash = ?"));
        assertTrue(result.sql().contains("`id_card_like` LIKE ?") || result.sql().contains("id_card_like LIKE ?"));
        assertTrue(result.sql().contains(" OR "));
        assertEquals(2, result.maskedParameters().size());
    }

    /**
     * 测试目的：验证查询条件中的加密字段会改写为辅助查询列或独立表 EXISTS 谓词。
     * 测试场景：构造等值、LIKE、空值、嵌套括号和子查询条件，断言 SQL 谓词和参数顺序保持正确。
     */
    @Test
    void shouldRewriteSeparateTableLikeConcatPatternUsingReferencedStorageId() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT u.id FROM user_account u WHERE u.status = ? AND u.id_card LIKE CONCAT('%', ?, '%')",
                List.of(
                        new ParameterMapping.Builder(configuration, "status", Integer.class).build(),
                        new ParameterMapping.Builder(configuration, "idCardSegment", String.class).build()
                ),
                Map.of("status", 1, "idCardSegment", "320101")
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

        assertTrue(result.changed());
        assertTrue(result.sql().contains("EXISTS"));
        assertTrue(result.sql().contains("`id_card_like` LIKE ?") || result.sql().contains("id_card_like LIKE ?"));
        assertTrue(result.sql().contains("`id_card_hash` = ?") || result.sql().contains("id_card_hash = ?"));
        assertTrue(result.sql().contains(" OR "));
        assertFalse(result.sql().contains("CONCAT("));
        result.applyTo(boundSql);
        assertEquals(3, boundSql.getParameterMappings().size());
        assertEquals("status", boundSql.getParameterMappings().get(0).getProperty());
        assertTrue(boundSql.getParameterMappings().get(1).getProperty().startsWith("__encrypt_generated_"));
        assertTrue(boundSql.getParameterMappings().get(2).getProperty().startsWith("__encrypt_generated_"));
        assertEquals("%320101%", boundSql.getAdditionalParameter(boundSql.getParameterMappings().get(1).getProperty()));
        assertEquals(new Sm3AssistedQueryAlgorithm().transform("320101"),
                boundSql.getAdditionalParameter(boundSql.getParameterMappings().get(2).getProperty()));
        assertEquals(2, result.maskedParameters().size());
    }

    /**
     * 测试目的：验证查询条件中的加密字段会改写为辅助查询列或独立表 EXISTS 谓词。
     * 测试场景：构造等值、LIKE、空值、嵌套括号和子查询条件，断言 SQL 谓词和参数顺序保持正确。
     */
    @Test
    void shouldRewriteSeparateTableIsNullUsingReferencedStorageId() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT u.id FROM user_account u WHERE u.id_card IS NULL",
                List.of(),
                Map.of()
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);
        System.out.println("IS NULL SQL => " + result.sql());

        assertTrue(result.changed());
        assertTrue(result.sql().contains("NOT EXISTS"));
        assertTrue(result.sql().contains("`id_card_hash` = u.`id_card`") || result.sql().contains("`id_card_hash` = `id_card`")
                || result.sql().contains("id_card_hash = u.id_card") || result.sql().contains("id_card_hash = id_card"));
    }

    /**
     * 测试目的：验证查询条件中的加密字段会改写为辅助查询列或独立表 EXISTS 谓词。
     * 测试场景：构造等值、LIKE、空值、嵌套括号和子查询条件，断言 SQL 谓词和参数顺序保持正确。
     */
    @Test
    void shouldRewriteSeparateTableIsNullAndIsNotNull() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql isNullSql = new BoundSql(
                configuration,
                "SELECT u.id FROM user_account u WHERE u.id_card IS NULL",
                List.of(),
                Map.of()
        );
        RewriteResult isNullResult = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), isNullSql);
        assertTrue(isNullResult.changed());
        assertTrue(isNullResult.sql().contains("NOT EXISTS"));
        assertTrue(isNullResult.sql().contains("`user_id_card_encrypt`"));

        BoundSql isNotNullSql = new BoundSql(
                configuration,
                "SELECT u.id FROM user_account u WHERE u.id_card IS NOT NULL",
                List.of(),
                Map.of()
        );
        RewriteResult isNotNullResult = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), isNotNullSql);
        assertTrue(isNotNullResult.changed());
        assertTrue(isNotNullResult.sql().contains("EXISTS"));
        assertFalse(isNotNullResult.sql().contains("NOT EXISTS"));
    }

    /**
     * 测试目的：验证查询条件中的加密字段会改写为辅助查询列或独立表 EXISTS 谓词。
     * 测试场景：构造等值、LIKE、空值、嵌套括号和子查询条件，断言 SQL 谓词和参数顺序保持正确。
     */
    @Test
    void shouldRewriteNestedConditionsContainingSeparateTablePredicates() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT u.id FROM user_account u WHERE (u.id_card = ? OR u.id_card LIKE ?) AND u.phone = ?",
                List.of(
                        new ParameterMapping.Builder(configuration, "idCard", String.class).build(),
                        new ParameterMapping.Builder(configuration, "idCardLike", String.class).build(),
                        new ParameterMapping.Builder(configuration, "phone", String.class).build()
                ),
                Map.of("idCard", "320101199001011234", "idCardLike", "%320101%", "phone", "13800138000")
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

        assertTrue(result.changed());
        assertTrue(result.sql().contains("`id_card_hash` = ?") || result.sql().contains("id_card_hash = ?"));
        assertTrue(result.sql().contains("`id_card_like` LIKE ?") || result.sql().contains("id_card_like LIKE ?"));
        assertTrue(result.sql().contains("u.`phone_hash` = ?") || result.sql().contains("u.phone_hash = ?"));
        assertTrue(result.sql().contains("`id_card_hash` = u.`id_card`") || result.sql().contains("`id_card_hash` = `id_card`")
                || result.sql().contains("id_card_hash = u.id_card") || result.sql().contains("id_card_hash = id_card"));
        assertEquals(4, result.maskedParameters().size());
    }

    /**
     * 测试目的：验证查询条件中的加密字段会改写为辅助查询列或独立表 EXISTS 谓词。
     * 测试场景：构造等值、LIKE、空值、嵌套括号和子查询条件，断言 SQL 谓词和参数顺序保持正确。
     */
    @Test
    void shouldRewriteSeparateTablePredicateWrappedByExtraParentheses() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT u.id FROM user_account u WHERE ((u.id_card = ?))",
                List.of(new ParameterMapping.Builder(configuration, "idCard", String.class).build()),
                Map.of("idCard", "320101199001011234")
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

        assertTrue(result.changed());
        assertTrue(result.sql().contains("EXISTS"));
        assertTrue(result.sql().contains("`user_id_card_encrypt`"));
        assertTrue(result.sql().contains("`id_card_hash` = u.`id_card`") || result.sql().contains("`id_card_hash` = `id_card`")
                || result.sql().contains("id_card_hash = u.id_card") || result.sql().contains("id_card_hash = id_card"));
        assertTrue(result.sql().contains("`id_card_hash` = ?") || result.sql().contains("id_card_hash = ?"));
        assertEquals(1, result.maskedParameters().size());
    }

    /**
     * 测试目的：验证查询条件中的加密字段会改写为辅助查询列或独立表 EXISTS 谓词。
     * 测试场景：构造等值、LIKE、空值、嵌套括号和子查询条件，断言 SQL 谓词和参数顺序保持正确。
     */
    @Test
    void shouldRewriteSeparateTableLikeWrappedByExtraParentheses() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT u.id FROM user_account u WHERE ((u.id_card LIKE ?))",
                List.of(new ParameterMapping.Builder(configuration, "idCardLike", String.class).build()),
                Map.of("idCardLike", "%320101%")
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

        assertTrue(result.changed());
        assertTrue(result.sql().contains("EXISTS"));
        assertTrue(result.sql().contains("`user_id_card_encrypt`"));
        assertTrue(result.sql().contains("`id_card_hash` = u.`id_card`") || result.sql().contains("`id_card_hash` = `id_card`")
                || result.sql().contains("id_card_hash = u.id_card") || result.sql().contains("id_card_hash = id_card"));
        assertTrue(result.sql().contains("`id_card_like` LIKE ?") || result.sql().contains("id_card_like LIKE ?"));
        assertEquals(2, result.maskedParameters().size());
    }

    /**
     * 测试目的：验证查询条件中的加密字段会改写为辅助查询列或独立表 EXISTS 谓词。
     * 测试场景：构造等值、LIKE、空值、嵌套括号和子查询条件，断言 SQL 谓词和参数顺序保持正确。
     */
    @Test
    void shouldRewriteSeparateTableIsNullWrappedByExtraParentheses() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT u.id FROM user_account u WHERE ((u.id_card IS NULL))",
                List.of(),
                Map.of()
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

        assertTrue(result.changed());
        assertTrue(result.sql().contains("NOT EXISTS"));
        assertTrue(result.sql().contains("`user_id_card_encrypt`"));
        assertTrue(result.sql().contains("`id_card_hash` = u.`id_card`") || result.sql().contains("`id_card_hash` = `id_card`")
                || result.sql().contains("id_card_hash = u.id_card") || result.sql().contains("id_card_hash = id_card"));
    }

    /**
     * 测试目的：验证查询条件中的加密字段会改写为辅助查询列或独立表 EXISTS 谓词。
     * 测试场景：构造等值、LIKE、空值、嵌套括号和子查询条件，断言 SQL 谓词和参数顺序保持正确。
     */
    @Test
    void shouldRewriteNestedParenthesizedSeparateTableEqualityOrIsNull() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT u.id FROM user_account u WHERE ((u.id_card = ?) OR (u.id_card IS NULL))",
                List.of(new ParameterMapping.Builder(configuration, "idCard", String.class).build()),
                Map.of("idCard", "320101199001011234")
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

        assertTrue(result.changed());
        assertTrue(result.sql().contains("EXISTS"));
        assertTrue(result.sql().contains("NOT EXISTS"));
        assertTrue(result.sql().contains("OR"));
        assertTrue(result.sql().contains("`user_id_card_encrypt`"));
        assertTrue(result.sql().contains("`id_card_hash` = ?") || result.sql().contains("id_card_hash = ?"));
        assertTrue(result.sql().contains("`id_card_hash` = u.`id_card`") || result.sql().contains("`id_card_hash` = `id_card`")
                || result.sql().contains("id_card_hash = u.id_card") || result.sql().contains("id_card_hash = id_card"));
        assertEquals(1, result.maskedParameters().size());
    }

    /**
     * 测试目的：验证查询条件中的加密字段会改写为辅助查询列或独立表 EXISTS 谓词。
     * 测试场景：构造等值、LIKE、空值、嵌套括号和子查询条件，断言 SQL 谓词和参数顺序保持正确。
     */
    @Test
    void shouldRewriteNestedParenthesizedSeparateTableLikeOrEquality() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT u.id FROM user_account u WHERE ((u.id_card LIKE ?) OR (u.id_card = ?))",
                List.of(
                        new ParameterMapping.Builder(configuration, "idCardLike", String.class).build(),
                        new ParameterMapping.Builder(configuration, "idCard", String.class).build()
                ),
                Map.of("idCardLike", "%320101%", "idCard", "320101199001011234")
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

        assertTrue(result.changed());
        assertTrue(result.sql().contains("EXISTS"));
        assertTrue(result.sql().contains("OR"));
        assertTrue(result.sql().contains("`user_id_card_encrypt`"));
        assertTrue(result.sql().contains("`id_card_like` LIKE ?") || result.sql().contains("id_card_like LIKE ?"));
        assertTrue(result.sql().contains("`id_card_hash` = ?") || result.sql().contains("id_card_hash = ?"));
        assertTrue(result.sql().contains("`id_card_hash` = u.`id_card`") || result.sql().contains("`id_card_hash` = `id_card`")
                || result.sql().contains("id_card_hash = u.id_card") || result.sql().contains("id_card_hash = id_card"));
        assertEquals(3, result.maskedParameters().size());
    }

    /**
     * 测试目的：验证查询条件中的加密字段会改写为辅助查询列或独立表 EXISTS 谓词。
     * 测试场景：构造等值、LIKE、空值、嵌套括号和子查询条件，断言 SQL 谓词和参数顺序保持正确。
     */
    @Test
    void shouldRewriteSeparateTablePredicateInsideExistsSubquery() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT o.id FROM order_account o WHERE EXISTS (" +
                        "SELECT 1 FROM user_account u WHERE u.id = o.user_id AND u.id_card = ?)",
                List.of(new ParameterMapping.Builder(configuration, "idCard", String.class).build()),
                Map.of("idCard", "320101199001011234")
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

        assertTrue(result.changed());
        assertTrue(result.sql().contains("WHERE EXISTS (SELECT 1 FROM user_account u"));
        assertTrue(result.sql().contains("`user_id_card_encrypt`"));
        assertTrue(result.sql().contains("`id_card_hash` = u.`id_card`") || result.sql().contains("`id_card_hash` = `id_card`")
                || result.sql().contains("id_card_hash = u.id_card") || result.sql().contains("id_card_hash = id_card"));
        assertTrue(result.sql().contains("`id_card_hash` = ?") || result.sql().contains("id_card_hash = ?"));
        assertEquals(1, result.maskedParameters().size());
    }

    /**
     * 测试目的：验证查询条件中的加密字段会改写为辅助查询列或独立表 EXISTS 谓词。
     * 测试场景：构造等值、LIKE、空值、嵌套括号和子查询条件，断言 SQL 谓词和参数顺序保持正确。
     */
    @Test
    void shouldPreserveSelectPlaceholderParameterOrderWhenRewritingSeparateTableWherePredicate() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT ? AS marker, u.name, u.phone FROM user_account u WHERE u.id_card = ?",
                List.of(
                        new ParameterMapping.Builder(configuration, "marker", String.class).build(),
                        new ParameterMapping.Builder(configuration, "idCard", String.class).build()
                ),
                Map.of("marker", "VISIBLE", "idCard", "320101199001011234")
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

        assertTrue(result.changed());
        assertTrue(result.sql().contains("? AS marker") || result.sql().contains("? marker"));
        assertTrue(result.sql().contains("u.name"));
        assertTrue(result.sql().contains("u.`phone_cipher` AS phone") || result.sql().contains("u.`phone_cipher` phone"));
        assertTrue(result.sql().contains("`user_id_card_encrypt`"));
        assertTrue(result.sql().contains("`id_card_hash` = ?") || result.sql().contains("id_card_hash = ?"));
        result.applyTo(boundSql);
        assertEquals(2, boundSql.getParameterMappings().size());
        assertEquals("marker", boundSql.getParameterMappings().get(0).getProperty());
        assertTrue(boundSql.getParameterMappings().get(1).getProperty().startsWith("__encrypt_generated_"));
        assertEquals(1, result.maskedParameters().size());
    }

    /**
     * 测试目的：验证查询条件中的加密字段会改写为辅助查询列或独立表 EXISTS 谓词。
     * 测试场景：构造等值、LIKE、空值、嵌套括号和子查询条件，断言 SQL 谓词和参数顺序保持正确。
     */
    @Test
    void shouldPreserveMultipleSelectPlaceholdersBeforeEncryptedWhereParameter() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT ? AS marker1, ? AS marker2, u.name, u.phone FROM user_account u WHERE u.id_card = ?",
                List.of(
                        new ParameterMapping.Builder(configuration, "marker1", String.class).build(),
                        new ParameterMapping.Builder(configuration, "marker2", String.class).build(),
                        new ParameterMapping.Builder(configuration, "idCard", String.class).build()
                ),
                Map.of("marker1", "VISIBLE-1", "marker2", "VISIBLE-2", "idCard", "320101199001011234")
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

        assertTrue(result.changed());
        assertTrue(result.sql().contains("? AS marker1") || result.sql().contains("? marker1"));
        assertTrue(result.sql().contains("? AS marker2") || result.sql().contains("? marker2"));
        assertTrue(result.sql().contains("`user_id_card_encrypt`"));
        assertTrue(result.sql().contains("`id_card_hash` = ?") || result.sql().contains("id_card_hash = ?"));
        result.applyTo(boundSql);
        assertEquals(3, boundSql.getParameterMappings().size());
        assertEquals("marker1", boundSql.getParameterMappings().get(0).getProperty());
        assertEquals("marker2", boundSql.getParameterMappings().get(1).getProperty());
        assertTrue(boundSql.getParameterMappings().get(2).getProperty().startsWith("__encrypt_generated_"));
        assertEquals(1, result.maskedParameters().size());
    }

    /**
     * 测试目的：验证查询条件中的加密字段会改写为辅助查询列或独立表 EXISTS 谓词。
     * 测试场景：构造等值、LIKE、空值、嵌套括号和子查询条件，断言 SQL 谓词和参数顺序保持正确。
     */
    @Test
    void shouldPreserveMultipleSelectPlaceholdersBeforeSameTableEncryptedWhereParameter() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT ? AS marker1, ? AS marker2, u.name, u.phone FROM user_account u WHERE u.phone = ?",
                List.of(
                        new ParameterMapping.Builder(configuration, "marker1", String.class).build(),
                        new ParameterMapping.Builder(configuration, "marker2", String.class).build(),
                        new ParameterMapping.Builder(configuration, "phone", String.class).build()
                ),
                Map.of("marker1", "VISIBLE-1", "marker2", "VISIBLE-2", "phone", "13800138000")
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

        assertTrue(result.changed());
        assertTrue(result.sql().contains("? AS marker1") || result.sql().contains("? marker1"));
        assertTrue(result.sql().contains("? AS marker2") || result.sql().contains("? marker2"));
        assertTrue(result.sql().contains("u.`phone_cipher` AS phone") || result.sql().contains("u.`phone_cipher` phone"));
        assertTrue(result.sql().contains("`phone_hash` = ?") || result.sql().contains("phone_hash = ?"));
        result.applyTo(boundSql);
        assertEquals(3, boundSql.getParameterMappings().size());
        assertEquals("marker1", boundSql.getParameterMappings().get(0).getProperty());
        assertEquals("marker2", boundSql.getParameterMappings().get(1).getProperty());
        assertTrue(boundSql.getParameterMappings().get(2).getProperty().startsWith("__encrypt_generated_"));
        assertEquals(1, result.maskedParameters().size());
    }

    /**
     * 测试目的：验证查询条件中的加密字段会改写为辅助查询列或独立表 EXISTS 谓词。
     * 测试场景：构造等值、LIKE、空值、嵌套括号和子查询条件，断言 SQL 谓词和参数顺序保持正确。
     */
    @Test
    void shouldPreserveCrmCustomerCountQueryParameterOrderWhenEncryptingOuterContactNamePredicate() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        DatabaseEncryptionProperties.TableRuleProperties contactTableRule =
                new DatabaseEncryptionProperties.TableRuleProperties();
        contactTableRule.setTable("crm_external_contact");
        DatabaseEncryptionProperties.FieldRuleProperties nameFieldRule =
                new DatabaseEncryptionProperties.FieldRuleProperties();
        nameFieldRule.setProperty("name");
        nameFieldRule.setColumn("name");
        nameFieldRule.setStorageColumn("name_cipher");
        nameFieldRule.setAssistedQueryColumn("name_hash");
        nameFieldRule.setLikeQueryColumn("name_like");
        contactTableRule.getFields().add(nameFieldRule);
        properties.getTables().add(contactTableRule);

        AlgorithmRegistry algorithmRegistry = sampleAlgorithms();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                algorithmRegistry,
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT COUNT(*) AS total FROM (" +
                        "SELECT a.external_userid, " +
                        "coalesce(max(CASE WHEN a.userid = ? THEN a.wx_createtime END), min(a.wx_createtime)) AS wx_createtime, " +
                        "max(a.userid = ?) AS is_edit, " +
                        "GROUP_CONCAT(a.remark) AS remark, " +
                        "GROUP_CONCAT(u.realname) AS gridman, " +
                        "GROUP_CONCAT(a.userid) AS userid " +
                        "FROM crm_follow_user a " +
                        "JOIN sys_user u ON a.userid = u.id " +
                        "WHERE a.del_flag = 0 AND a.external_userid IS NOT NULL " +
                        "GROUP BY a.external_userid) a " +
                        "JOIN crm_external_contact e ON a.external_userid = e.id " +
                        "JOIN crm_cust_info c ON e.external_userid = c.external_userid AND c.del_flag = 0 " +
                        "LEFT JOIN crm_cust_filter f ON c.id = f.cust_id AND f.del_flag = 0 " +
                        "LEFT JOIN hx_bank_cust_tag t ON c.id_card = t.id_card " +
                        "WHERE (a.userid REGEXP 'ff8080818a44c9fb018a44c9fb5d0000' " +
                        "AND (e.name = ? " +
                        "OR EXISTS (SELECT 1 FROM `crm_encrypt_name` WHERE `id` = c.`realname` AND `hash` = ?) " +
                        "OR a.remark = ?))",
                List.of(
                        new ParameterMapping.Builder(configuration, "userIdForCase", String.class).build(),
                        new ParameterMapping.Builder(configuration, "userIdForMax", String.class).build(),
                        new ParameterMapping.Builder(configuration, "contactName", String.class).build(),
                        new ParameterMapping.Builder(configuration, "realnameHash", String.class).build(),
                        new ParameterMapping.Builder(configuration, "remark", String.class).build()
                ),
                Map.of(
                        "userIdForCase", "ff8080818a44c9fb018a44c9fb5d0000",
                        "userIdForMax", "ff8080818a44c9fb018a44c9fb5d0000",
                        "contactName", "测试1",
                        "realnameHash", "8c5342b5f4af7646e4bb90f7a3eda3e8da58272edcf7e764de2ade614f061ae8",
                        "remark", "测试1"
                )
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

        assertTrue(result.changed());
        assertTrue(result.sql().contains(
                "SELECT COUNT(*) AS total FROM (SELECT a.external_userid, " +
                        "coalesce(max(CASE WHEN a.userid = ? THEN a.wx_createtime END), min(a.wx_createtime)) AS wx_createtime, " +
                        "max(a.userid = ?) AS is_edit, GROUP_CONCAT(a.remark) AS remark, GROUP_CONCAT(u.realname) AS gridman, " +
                        "GROUP_CONCAT(a.userid) AS userid FROM crm_follow_user a JOIN sys_user u ON a.userid = u.id " +
                        "WHERE a.del_flag = 0 AND a.external_userid IS NOT NULL GROUP BY a.external_userid) a " +
                        "JOIN crm_external_contact e ON a.external_userid = e.id " +
                        "JOIN crm_cust_info c ON e.external_userid = c.external_userid AND c.del_flag = 0 " +
                        "LEFT JOIN crm_cust_filter f ON c.id = f.cust_id AND f.del_flag = 0 " +
                        "LEFT JOIN hx_bank_cust_tag t ON c.id_card = t.id_card"), result.sql());
        assertTrue(result.sql().contains(
                "WHERE (a.userid REGEXP 'ff8080818a44c9fb018a44c9fb5d0000' " +
                        "AND (e.`name_hash` = ? OR EXISTS (SELECT 1 FROM `crm_encrypt_name` WHERE `id` = c.`realname` AND `hash` = ?) OR a.remark = ?))"), result.sql());
        result.applyTo(boundSql);
        assertEquals(5, boundSql.getParameterMappings().size());
        assertEquals("userIdForCase", boundSql.getParameterMappings().get(0).getProperty());
        assertEquals("userIdForMax", boundSql.getParameterMappings().get(1).getProperty());
        assertTrue(boundSql.getParameterMappings().get(2).getProperty().startsWith("__encrypt_generated_"));
        assertEquals("realnameHash", boundSql.getParameterMappings().get(3).getProperty());
        assertEquals("remark", boundSql.getParameterMappings().get(4).getProperty());
        assertEquals(
                algorithmRegistry.assisted("sm3").transform("测试1"),
                boundSql.getAdditionalParameter(boundSql.getParameterMappings().get(2).getProperty())
        );
    }

    /**
     * 测试目的：验证写入类 SQL 会把明文字段改写为密文列、辅助查询列、LIKE 列和脱敏列。
     * 测试场景：构造 INSERT/UPDATE/DELETE 语句和 MyBatis 参数绑定，断言改写后的 SQL、生成参数和写入列集合正确。
     */
    @Test
    void shouldRewriteUpdateAcrossStorageModes() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "UPDATE user_account SET phone = ?, id_card = ? WHERE phone = ? AND id_card = ?",
                List.of(
                        new ParameterMapping.Builder(configuration, "phone", String.class).build(),
                        new ParameterMapping.Builder(configuration, "idCard", String.class).build(),
                        new ParameterMapping.Builder(configuration, "wherePhone", String.class).build(),
                        new ParameterMapping.Builder(configuration, "whereIdCard", String.class).build()
                ),
                Map.of(
                        "phone", "13800138001",
                        "idCard", "320101199001011235",
                        "wherePhone", "13800138000",
                        "whereIdCard", "320101199001011234"
                )
        );
        boundSql.setAdditionalParameter("idCard", "1002");

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.UPDATE, Map.class), boundSql);

        assertTrue(result.changed());
        assertTrue(result.sql().contains("`phone_cipher` = ?") || result.sql().contains("phone_cipher = ?"));
        assertTrue(result.sql().contains("`phone_hash` = ?") || result.sql().contains("phone_hash = ?"));
        assertTrue(result.sql().contains("`phone_like` = ?") || result.sql().contains("phone_like = ?"));
        assertTrue(result.sql().contains("`phone_masked` = ?") || result.sql().contains("phone_masked = ?"));
        assertTrue(result.sql().contains("`id_card` = ?") || result.sql().contains("id_card = ?"));
        assertTrue(result.sql().contains("WHERE `phone_hash` = ?") || result.sql().contains("WHERE phone_hash = ?"));
        assertTrue(result.sql().contains("EXISTS"));
        assertTrue(result.sql().contains("`id_card_hash` = ?") || result.sql().contains("id_card_hash = ?"));
        result.applyTo(boundSql);
        assertEquals(7, boundSql.getParameterMappings().size());
    }

    /**
     * 测试目的：验证写入类 SQL 会把明文字段改写为密文列、辅助查询列、LIKE 列和脱敏列。
     * 测试场景：构造 INSERT/UPDATE/DELETE 语句和 MyBatis 参数绑定，断言改写后的 SQL、生成参数和写入列集合正确。
     */
    @Test
    void shouldRewriteUpdateOnceWhenLikeAndMaskedShareColumn() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = samplePropertiesWithSharedLikeMaskedColumn();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "UPDATE user_account SET phone = ? WHERE id = ?",
                List.of(
                        new ParameterMapping.Builder(configuration, "phone", String.class).build(),
                        new ParameterMapping.Builder(configuration, "id", Long.class).build()
                ),
                Map.of("phone", "13800138000", "id", 1L)
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.UPDATE, Map.class), boundSql);

        assertTrue(result.changed());
        assertEquals(1, occurrences(result.sql(), "`phone_like` = ?"));
        assertFalse(result.sql().contains("`phone_masked`"));
        result.applyTo(boundSql);
        assertEquals(4, boundSql.getParameterMappings().size());
    }

    /**
     * 测试目的：验证写入类 SQL 会把明文字段改写为密文列、辅助查询列、LIKE 列和脱敏列。
     * 测试场景：构造 INSERT/UPDATE/DELETE 语句和 MyBatis 参数绑定，断言改写后的 SQL、生成参数和写入列集合正确。
     */
    @Test
    void shouldTreatLiteralUpdateSetRewriteAsChanged() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "UPDATE user_account SET phone = '13800138000' WHERE id = 1",
                List.of(),
                Map.of()
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.UPDATE, Map.class), boundSql);

        assertTrue(result.changed());
        assertTrue(result.sql().contains("`phone_cipher` = '"));
        assertTrue(result.sql().contains("`phone_hash` = '"));
        assertTrue(result.sql().contains("`phone_like` = '"));
    }

    /**
     * 测试目的：验证写入类 SQL 会把明文字段改写为密文列、辅助查询列、LIKE 列和脱敏列。
     * 测试场景：构造 INSERT/UPDATE/DELETE 语句和 MyBatis 参数绑定，断言改写后的 SQL、生成参数和写入列集合正确。
     */
    @Test
    void shouldRewriteDeleteAcrossStorageModes() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "DELETE FROM user_account WHERE phone = ? OR id_card = ?",
                List.of(
                        new ParameterMapping.Builder(configuration, "phone", String.class).build(),
                        new ParameterMapping.Builder(configuration, "idCard", String.class).build()
                ),
                Map.of("phone", "13800138000", "idCard", "320101199001011234")
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.DELETE, Map.class), boundSql);

        assertTrue(result.changed());
        assertTrue(result.sql().contains("`phone_hash` = ?"));
        assertTrue(result.sql().contains("EXISTS"));
        assertTrue(result.sql().contains("`user_id_card_encrypt`"));
        assertTrue(result.sql().contains("`id_card_hash` = ?") || result.sql().contains("id_card_hash = ?"));
        assertEquals(2, result.maskedParameters().size());
    }

    /**
     * 测试目的：验证查询条件中的加密字段会改写为辅助查询列或独立表 EXISTS 谓词。
     * 测试场景：构造等值、LIKE、空值、嵌套括号和子查询条件，断言 SQL 谓词和参数顺序保持正确。
     */
    @Test
    void shouldRewriteHavingCondition() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT count(1) total FROM user_account HAVING phone = ?",
                List.of(new ParameterMapping.Builder(configuration, "phone", String.class).build()),
                Map.of("phone", "13800138000")
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

        assertTrue(result.changed());
        assertTrue(result.sql().contains("HAVING `phone_hash` = ?"));
    }

    /**
     * 测试目的：验证不支持或高风险的加密字段 SQL 会按安全策略快速失败。
     * 测试场景：构造 ORDER BY、聚合、范围条件、歧义列或非法操作数等 SQL，断言异常类型和错误码符合约束。
     */
    @Test
    void shouldFailFastForEncryptedGroupBy() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT phone FROM user_account GROUP BY phone",
                List.of(),
                Map.of()
        );

        UnsupportedEncryptedOperationException exception = assertThrows(UnsupportedEncryptedOperationException.class,
                () -> engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql));
        assertEquals(EncryptionErrorCode.UNSUPPORTED_ENCRYPTED_GROUP_BY, exception.getErrorCode());
    }

    /**
     * 测试目的：验证不支持或高风险的加密字段 SQL 会按安全策略快速失败。
     * 测试场景：构造 ORDER BY、聚合、范围条件、歧义列或非法操作数等 SQL，断言异常类型和错误码符合约束。
     */
    @Test
    void shouldFailFastForEncryptedOrderBy() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT id FROM user_account ORDER BY phone",
                List.of(),
                Map.of()
        );

        UnsupportedEncryptedOperationException exception = assertThrows(UnsupportedEncryptedOperationException.class,
                () -> engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql));
        assertEquals(EncryptionErrorCode.UNSUPPORTED_ENCRYPTED_ORDER_BY, exception.getErrorCode());
        assertEquals("ORDER BY is not supported on encrypted field: phone", exception.getMessage());
    }

    /**
     * 测试目的：验证不支持或高风险的加密字段 SQL 会按安全策略快速失败。
     * 测试场景：构造 ORDER BY、聚合、范围条件、歧义列或非法操作数等 SQL，断言异常类型和错误码符合约束。
     */
    @Test
    void shouldFailFastForEncryptedRangePredicate() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT id FROM user_account WHERE phone > ?",
                List.of(new ParameterMapping.Builder(configuration, "phone", String.class).build()),
                Map.of("phone", "13800138000")
        );

        UnsupportedEncryptedOperationException exception = assertThrows(UnsupportedEncryptedOperationException.class,
                () -> engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql));
        assertEquals(EncryptionErrorCode.UNSUPPORTED_ENCRYPTED_RANGE, exception.getErrorCode());
        assertEquals("Range comparison is not supported on encrypted fields.", exception.getMessage());
    }

    /**
     * 测试目的：验证查询条件中的加密字段会改写为辅助查询列或独立表 EXISTS 谓词。
     * 测试场景：构造等值、LIKE、空值、嵌套括号和子查询条件，断言 SQL 谓词和参数顺序保持正确。
     */
    @Test
    void shouldRewriteCaseWhenCondition() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT id FROM user_account WHERE CASE WHEN phone = ? THEN 1 ELSE 0 END = 1",
                List.of(new ParameterMapping.Builder(configuration, "phone", String.class).build()),
                Map.of("phone", "13800138000")
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

        assertTrue(result.changed());
        assertTrue(result.sql().contains("CASE WHEN `phone_hash` = ? THEN 1 ELSE 0 END = 1"));
    }

    /**
     * 测试目的：验证查询条件中的加密字段会改写为辅助查询列或独立表 EXISTS 谓词。
     * 测试场景：构造等值、LIKE、空值、嵌套括号和子查询条件，断言 SQL 谓词和参数顺序保持正确。
     */
    @Test
    void shouldRewriteQualifyCondition() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT id, ROW_NUMBER() OVER (ORDER BY id) rn FROM user_account QUALIFY phone = ?",
                List.of(new ParameterMapping.Builder(configuration, "phone", String.class).build()),
                Map.of("phone", "13800138000")
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

        assertTrue(result.changed());
        assertTrue(result.sql().contains("QUALIFY `phone_hash` = ?"));
    }

    /**
     * 测试目的：验证不支持或高风险的加密字段 SQL 会按安全策略快速失败。
     * 测试场景：构造 ORDER BY、聚合、范围条件、歧义列或非法操作数等 SQL，断言异常类型和错误码符合约束。
     */
    @Test
    void shouldFailFastForWindowFunctionUsingEncryptedField() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT ROW_NUMBER() OVER (PARTITION BY phone ORDER BY id) rn FROM user_account",
                List.of(),
                Map.of()
        );

        UnsupportedEncryptedOperationException exception = assertThrows(UnsupportedEncryptedOperationException.class,
                () -> engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql));
        assertEquals(EncryptionErrorCode.UNSUPPORTED_ENCRYPTED_WINDOW, exception.getErrorCode());
    }

    /**
     * 测试目的：验证不支持或高风险的加密字段 SQL 会按安全策略快速失败。
     * 测试场景：构造 ORDER BY、聚合、范围条件、歧义列或非法操作数等 SQL，断言异常类型和错误码符合约束。
     */
    @Test
    void shouldFailFastForDistinctEncryptedField() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT DISTINCT phone FROM user_account",
                List.of(),
                Map.of()
        );

        UnsupportedEncryptedOperationException exception = assertThrows(UnsupportedEncryptedOperationException.class,
                () -> engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql));
        assertEquals(EncryptionErrorCode.UNSUPPORTED_ENCRYPTED_DISTINCT, exception.getErrorCode());
    }

    /**
     * 测试目的：验证 SQL 改写核心组件在当前语句结构下保持安全且确定的改写行为。
     * 测试场景：构造对应 SQL、加密规则和参数上下文，断言 AST 改写结果、参数绑定和安全边界。
     */
    @Test
    void shouldAllowDistinctNonEncryptedField() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT DISTINCT id FROM user_account",
                List.of(),
                Map.of()
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);
        assertFalse(result.changed());
    }

    /**
     * 测试目的：验证不支持或高风险的加密字段 SQL 会按安全策略快速失败。
     * 测试场景：构造 ORDER BY、聚合、范围条件、歧义列或非法操作数等 SQL，断言异常类型和错误码符合约束。
     */
    @Test
    void shouldFailFastForAggregateUsingEncryptedField() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql countSql = new BoundSql(
                configuration,
                "SELECT COUNT(phone) FROM user_account",
                List.of(),
                Map.of()
        );

        UnsupportedEncryptedOperationException countException = assertThrows(UnsupportedEncryptedOperationException.class,
                () -> engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), countSql));
        assertEquals(EncryptionErrorCode.UNSUPPORTED_ENCRYPTED_AGGREGATION, countException.getErrorCode());

        BoundSql havingSql = new BoundSql(
                configuration,
                "SELECT COUNT(*) FROM user_account HAVING SUM(phone) > 0",
                List.of(),
                Map.of()
        );

        UnsupportedEncryptedOperationException havingException = assertThrows(UnsupportedEncryptedOperationException.class,
                () -> engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), havingSql));
        assertEquals(EncryptionErrorCode.UNSUPPORTED_ENCRYPTED_AGGREGATION, havingException.getErrorCode());
    }

    /**
     * 测试目的：验证不支持或高风险的加密字段 SQL 会按安全策略快速失败。
     * 测试场景：构造 ORDER BY、聚合、范围条件、歧义列或非法操作数等 SQL，断言异常类型和错误码符合约束。
     */
    @Test
    void shouldFailFastForAmbiguousEncryptedColumnReference() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT id FROM user_account u JOIN user_archive a ON u.id = a.id WHERE phone = ?",
                List.of(new ParameterMapping.Builder(configuration, "phone", String.class).build()),
                Map.of("phone", "13800138000")
        );

        UnsupportedEncryptedOperationException exception = assertThrows(UnsupportedEncryptedOperationException.class,
                () -> engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql));

        assertEquals(EncryptionErrorCode.AMBIGUOUS_ENCRYPTED_REFERENCE, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("Ambiguous encrypted column reference"));
    }

    /**
     * 测试目的：验证不支持或高风险的加密字段 SQL 会按安全策略快速失败。
     * 测试场景：构造 ORDER BY、聚合、范围条件、歧义列或非法操作数等 SQL，断言异常类型和错误码符合约束。
     */
    @Test
    void shouldFailFastForEncryptedQueryUsingNonLiteralOperand() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT id FROM user_account WHERE phone = name",
                List.of(),
                Map.of()
        );

        UnsupportedEncryptedOperationException exception = assertThrows(UnsupportedEncryptedOperationException.class,
                () -> engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql));

        assertEquals(EncryptionErrorCode.INVALID_ENCRYPTED_QUERY_OPERAND, exception.getErrorCode());
        assertEquals("Encrypted query condition must use prepared parameter or string literal.", exception.getMessage());
    }

    /**
     * 测试目的：验证 SQL 改写核心组件在当前语句结构下保持安全且确定的改写行为。
     * 测试场景：构造对应 SQL、加密规则和参数上下文，断言 AST 改写结果、参数绑定和安全边界。
     */
    @Test
    void shouldAllowCountStar() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT COUNT(*) FROM user_account",
                List.of(),
                Map.of()
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);
        assertFalse(result.changed());
    }

    /**
     * 测试目的：验证 SQL 改写核心组件在当前语句结构下保持安全且确定的改写行为。
     * 测试场景：构造对应 SQL、加密规则和参数上下文，断言 AST 改写结果、参数绑定和安全边界。
     */
    @Test
    void shouldRewriteAcrossStorageModesUsingOracle12Dialect() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties(SqlDialect.ORACLE12);
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT u.id, u.phone, u.id_card FROM user_account u WHERE u.phone = ? AND u.id_card = ?",
                List.of(
                        new ParameterMapping.Builder(configuration, "phone", String.class).build(),
                        new ParameterMapping.Builder(configuration, "idCard", String.class).build()
                ),
                Map.of("phone", "13800138000", "idCard", "320101199001011234")
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

        assertTrue(result.changed());
        assertTrue(result.sql().contains("u.\"phone_cipher\" AS phone") || result.sql().contains("u.\"phone_cipher\" phone"));
        assertTrue(result.sql().contains("u.\"phone_hash\" = ?") || result.sql().contains("\"phone_hash\" = ?"));
        assertTrue(result.sql().contains("\"user_id_card_encrypt\""));
        assertTrue(result.sql().contains("\"id_card_hash\" = ?"));
        result.applyTo(boundSql);
        assertEquals(2, boundSql.getParameterMappings().size());
        assertTrue(boundSql.getParameterMappings().get(0).getProperty().startsWith("__encrypt_generated_"));
        assertTrue(boundSql.getParameterMappings().get(1).getProperty().startsWith("__encrypt_generated_"));
        assertEquals(2, result.maskedParameters().size());
    }

    /**
     * 测试目的：验证 SQL 改写核心组件在当前语句结构下保持安全且确定的改写行为。
     * 测试场景：构造对应 SQL、加密规则和参数上下文，断言 AST 改写结果、参数绑定和安全边界。
     */
    @Test
    void shouldRewriteAcrossStorageModesUsingClickHouseDialect() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties(SqlDialect.CLICKHOUSE);
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT u.id, u.phone, u.id_card FROM user_account u WHERE u.phone = ? AND u.id_card = ?",
                List.of(
                        new ParameterMapping.Builder(configuration, "phone", String.class).build(),
                        new ParameterMapping.Builder(configuration, "idCard", String.class).build()
                ),
                Map.of("phone", "13800138000", "idCard", "320101199001011234")
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

        assertTrue(result.changed());
        assertTrue(result.sql().contains("u.`phone_cipher` AS phone") || result.sql().contains("u.`phone_cipher` phone"));
        assertTrue(result.sql().contains("u.`phone_hash` = ?") || result.sql().contains("`phone_hash` = ?"));
        assertTrue(result.sql().contains("`user_id_card_encrypt`"));
        assertTrue(result.sql().contains("`id_card_hash` = ?"));
        result.applyTo(boundSql);
        assertEquals(2, boundSql.getParameterMappings().size());
        assertTrue(boundSql.getParameterMappings().get(0).getProperty().startsWith("__encrypt_generated_"));
        assertTrue(boundSql.getParameterMappings().get(1).getProperty().startsWith("__encrypt_generated_"));
        assertEquals(2, result.maskedParameters().size());
    }

    private DatabaseEncryptionProperties sampleProperties() {
        DatabaseEncryptionProperties properties = new DatabaseEncryptionProperties();
        DatabaseEncryptionProperties.TableRuleProperties tableRule = new DatabaseEncryptionProperties.TableRuleProperties();
        tableRule.setTable("user_account");
        DatabaseEncryptionProperties.FieldRuleProperties fieldRule = new DatabaseEncryptionProperties.FieldRuleProperties();
        fieldRule.setProperty("phone");
        fieldRule.setColumn("phone");
        fieldRule.setStorageColumn("phone_cipher");
        fieldRule.setAssistedQueryColumn("phone_hash");
        fieldRule.setLikeQueryColumn("phone_like");
        fieldRule.setMaskedColumn("phone_masked");
        fieldRule.setMaskedAlgorithm("phoneMaskLike");
        tableRule.getFields().add(fieldRule);

        DatabaseEncryptionProperties.FieldRuleProperties separateFieldRule = new DatabaseEncryptionProperties.FieldRuleProperties();
        separateFieldRule.setProperty("idCard");
        separateFieldRule.setColumn("id_card");
        separateFieldRule.setStorageMode(FieldStorageMode.SEPARATE_TABLE);
        separateFieldRule.setStorageTable("user_id_card_encrypt");
        separateFieldRule.setStorageColumn("id_card_cipher");
        separateFieldRule.setStorageIdColumn("id");
        separateFieldRule.setAssistedQueryColumn("id_card_hash");
        separateFieldRule.setLikeQueryColumn("id_card_like");
        separateFieldRule.setMaskedColumn("id_card_masked");
        separateFieldRule.setMaskedAlgorithm("idCardMaskLike");
        tableRule.getFields().add(separateFieldRule);
        properties.getTables().add(tableRule);

        DatabaseEncryptionProperties.TableRuleProperties archiveTableRule = new DatabaseEncryptionProperties.TableRuleProperties();
        archiveTableRule.setTable("user_archive");
        DatabaseEncryptionProperties.FieldRuleProperties archiveFieldRule = new DatabaseEncryptionProperties.FieldRuleProperties();
        archiveFieldRule.setProperty("phone");
        archiveFieldRule.setColumn("phone");
        archiveFieldRule.setStorageColumn("archive_phone_cipher");
        archiveFieldRule.setAssistedQueryColumn("archive_phone_hash");
        archiveFieldRule.setLikeQueryColumn("archive_phone_like");
        archiveFieldRule.setMaskedColumn("archive_phone_masked");
        archiveFieldRule.setMaskedAlgorithm("phoneMaskLike");
        archiveTableRule.getFields().add(archiveFieldRule);
        properties.getTables().add(archiveTableRule);

        properties.setDefaultCipherKey("unit-test-key");
        return properties;
    }

    private DatabaseEncryptionProperties sampleProperties(SqlDialect sqlDialect) {
        DatabaseEncryptionProperties properties = sampleProperties();
        properties.setSqlDialect(sqlDialect);
        return properties;
    }

    private DatabaseEncryptionProperties samplePropertiesWithSharedLikeMaskedColumn() {
        DatabaseEncryptionProperties properties = sampleProperties();
        DatabaseEncryptionProperties.FieldRuleProperties phoneRule = properties.getTables().get(0).getFields().get(0);
        phoneRule.setLikeQueryAlgorithm("phoneMaskLike");
        phoneRule.setMaskedColumn("phone_like");
        phoneRule.setMaskedAlgorithm("phoneMaskLike");
        return properties;
    }

    private DatabaseEncryptionProperties samplePropertiesWithInlineStorageColumn() {
        DatabaseEncryptionProperties properties = sampleProperties();
        properties.getTables().get(0).getFields().get(0).setStorageColumn("phone");
        return properties;
    }

    private DatabaseEncryptionProperties samplePropertiesWithoutPhoneAssistedQueryColumn() {
        DatabaseEncryptionProperties properties = sampleProperties();
        properties.getTables().get(0).getFields().get(0).setAssistedQueryColumn(null);
        return properties;
    }

    private DatabaseEncryptionProperties samplePropertiesWithoutPhoneLikeQueryColumn() {
        DatabaseEncryptionProperties properties = sampleProperties();
        properties.getTables().get(0).getFields().get(0).setLikeQueryColumn(null);
        return properties;
    }

    private DatabaseEncryptionProperties samplePropertiesWithoutSeparateTableAssistedQueryColumn() {
        DatabaseEncryptionProperties properties = sampleProperties();
        properties.getTables().get(0).getFields().get(1).setAssistedQueryColumn(null);
        return properties;
    }

    private AlgorithmRegistry sampleAlgorithms() {
        return new AlgorithmRegistry(
                Map.of("sm4", new Sm4CipherAlgorithm("unit-test-key")),
                Map.of("sm3", new Sm3AssistedQueryAlgorithm()),
                Map.of(
                        "normalizedLike", new NormalizedLikeQueryAlgorithm(),
                        "phoneMaskLike", new io.github.jasper.mybatis.encrypt.algorithm.support.PhoneNumberMaskLikeQueryAlgorithm(),
                        "idCardMaskLike", new io.github.jasper.mybatis.encrypt.algorithm.support.IdCardMaskLikeQueryAlgorithm()
                )
        );
    }

    private MappedStatement mappedStatement(Configuration configuration, SqlCommandType commandType, Class<?> parameterType) {
        SqlSource sqlSource = parameterObject -> null;
        ParameterMap parameterMap = new ParameterMap.Builder(configuration, "pm", parameterType, List.of()).build();
        ResultMap resultMap = new ResultMap.Builder(configuration, "rm", Map.class, List.of()).build();
        return new MappedStatement.Builder(configuration, "test." + commandType.name().toLowerCase(), sqlSource, commandType)
                .parameterMap(parameterMap)
                .resultMaps(List.of(resultMap))
                .build();
    }

    private int occurrences(String value, String token) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }
}
