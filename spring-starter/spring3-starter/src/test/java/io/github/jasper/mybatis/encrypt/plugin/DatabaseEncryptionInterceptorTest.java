package io.github.jasper.mybatis.encrypt.plugin;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.algorithm.support.NormalizedLikeQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.support.Sm3AssistedQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.support.Sm4CipherAlgorithm;
import io.github.jasper.mybatis.encrypt.annotation.EncryptField;
import io.github.jasper.mybatis.encrypt.annotation.EncryptTable;
import io.github.jasper.mybatis.encrypt.annotation.SkipSqlRewrite;
import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.core.decrypt.ResultDecryptor;
import io.github.jasper.mybatis.encrypt.core.metadata.AnnotationEncryptMetadataLoader;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import io.github.jasper.mybatis.encrypt.core.metadata.FieldStorageMode;
import io.github.jasper.mybatis.encrypt.core.rewrite.SqlRewriteEngine;
import io.github.jasper.mybatis.encrypt.core.support.SeparateTableEncryptionManager;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.BatchResult;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.resultset.ResultSetHandler;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.sql.CallableStatement;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
@Tag("plugin")
class DatabaseEncryptionInterceptorTest {

    /**
     * 测试目的：验证 MyBatis 插件在 Executor 和 ResultSet 阶段的拦截顺序与职责边界。
     * 测试场景：模拟查询、更新和结果集处理调用，断言 SQL 改写、独立表预处理和结果解密只在正确阶段发生。
     */
    @Test
    void shouldRewriteUpdateAtExecutorLayer() throws Throwable {
        Configuration configuration = new Configuration();
        TestExecutor executor = new TestExecutor();
        DatabaseEncryptionInterceptor interceptor = interceptor(null);
        MappedStatement mappedStatement = mappedStatement(
                configuration,
                "test.updatePhone",
                SqlCommandType.UPDATE,
                Map.class,
                "update user_account set phone = ? where id = ?",
                List.of(
                        new ParameterMapping.Builder(configuration, "phone", String.class).build(),
                        new ParameterMapping.Builder(configuration, "id", Long.class).build()
                )
        );

        Invocation invocation = new Invocation(
                executor,
                executorMethod("update", MappedStatement.class, Object.class),
                new Object[]{mappedStatement, Map.of("phone", "13800138000", "id", 1L)}
        );

        interceptor.intercept(invocation);

        assertNotSame(mappedStatement, executor.lastMappedStatement);
        assertTrue(executor.lastBoundSql.getSql().contains("phone_cipher"));
        assertTrue(executor.lastBoundSql.getSql().contains("phone_hash"));
        assertTrue(executor.lastBoundSql.getSql().contains("phone_like"));
        assertEquals(4, executor.lastBoundSql.getParameterMappings().size());
    }

    /**
     * 测试目的：验证 MyBatis 插件在 Executor 和 ResultSet 阶段的拦截顺序与职责边界。
     * 测试场景：模拟查询、更新和结果集处理调用，断言 SQL 改写、独立表预处理和结果解密只在正确阶段发生。
     */
    @Test
    void shouldReuseProvidedBoundSqlForSixArgQuery() throws Throwable {
        Configuration configuration = new Configuration();
        TestExecutor executor = new TestExecutor();
        DatabaseEncryptionInterceptor interceptor = interceptor(null);
        MappedStatement mappedStatement = mappedStatement(
                configuration,
                "test.selectByPhone",
                SqlCommandType.SELECT,
                Map.class,
                "select id from user_account where id = ?",
                List.of(new ParameterMapping.Builder(configuration, "id", Long.class).build())
        );
        BoundSql provided = new BoundSql(
                configuration,
                "select id, phone from user_account where phone = ?",
                List.of(new ParameterMapping.Builder(configuration, "phone", String.class).build()),
                Map.of("phone", "13800138000")
        );

        Invocation invocation = new Invocation(
                executor,
                executorMethod("query", MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class,
                        CacheKey.class, BoundSql.class),
                new Object[]{mappedStatement, Map.of("phone", "13800138000"), RowBounds.DEFAULT, null,
                        new CacheKey(), provided}
        );

        interceptor.intercept(invocation);

        assertSame(provided, executor.lastBoundSql);
        assertTrue(executor.lastBoundSql.getSql().contains("phone_hash"));
        assertTrue(executor.lastBoundSql.getSql().contains("phone_cipher"));
    }

    /**
     * 测试目的：验证 MyBatis 插件在 Executor 和 ResultSet 阶段的拦截顺序与职责边界。
     * 测试场景：模拟查询、更新和结果集处理调用，断言 SQL 改写、独立表预处理和结果解密只在正确阶段发生。
     */
    @Test
    void shouldNotResolvePlanDuringExecutorQueryInterception() throws Throwable {
        Configuration configuration = new Configuration();
        TestExecutor executor = new TestExecutor();
        RecordingResultDecryptor decryptor = recordingDecryptor();
        DatabaseEncryptionInterceptor interceptor = interceptor(null, decryptor);
        MappedStatement mappedStatement = mappedStatement(
                configuration,
                "test.selectByPhone",
                SqlCommandType.SELECT,
                Map.class,
                "select id from user_account where phone = ?",
                List.of(new ParameterMapping.Builder(configuration, "phone", String.class).build())
        );

        Invocation invocation = new Invocation(
                executor,
                executorMethod("query", MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class),
                new Object[]{mappedStatement, Map.of("phone", "13800138000"), RowBounds.DEFAULT, null}
        );

        interceptor.intercept(invocation);

        assertEquals(0, decryptor.resolvePlanCalls);
        assertEquals(0, decryptor.decryptWithPlanCalls);
    }

    /**
     * 测试目的：验证 MyBatis 插件在 Executor 和 ResultSet 阶段的拦截顺序与职责边界。
     * 测试场景：模拟查询、更新和结果集处理调用，断言 SQL 改写、独立表预处理和结果解密只在正确阶段发生。
     */
    @Test
    void shouldResolvePlanAndDecryptDuringResultSetInterception() throws Throwable {
        Configuration configuration = new Configuration();
        RecordingResultDecryptor decryptor = recordingDecryptor();
        DatabaseEncryptionInterceptor interceptor = interceptor(null, decryptor);
        MappedStatement mappedStatement = mappedStatement(
                configuration,
                "test.selectProjection",
                SqlCommandType.SELECT,
                Map.class,
                "select id from user_account where id = ?",
                List.of(new ParameterMapping.Builder(configuration, "id", Long.class).build())
        );
        BoundSql boundSql = mappedStatement.getBoundSql(Map.of("id", 1L));
        TestResultSetHandler handler = new TestResultSetHandler(mappedStatement, boundSql, List.of(Map.of("id", 1L)));

        Invocation invocation = new Invocation(
                handler,
                resultSetHandlerMethod("handleResultSets", Statement.class),
                new Object[]{null}
        );

        Object result = interceptor.intercept(invocation);

        assertSame(handler.result, result);
        assertEquals(1, decryptor.resolvePlanCalls);
        assertEquals(1, decryptor.decryptWithPlanCalls);
    }

    /**
     * 测试目的：验证 MyBatis 插件在 Executor 和 ResultSet 阶段的拦截顺序与职责边界。
     * 测试场景：模拟查询、更新和结果集处理调用，断言 SQL 改写、独立表预处理和结果解密只在正确阶段发生。
     */
    @Test
    void shouldBypassPlanResolutionForEmptyResultSetInterception() throws Throwable {
        Configuration configuration = new Configuration();
        RecordingResultDecryptor decryptor = recordingDecryptor();
        DatabaseEncryptionInterceptor interceptor = interceptor(null, decryptor);
        MappedStatement mappedStatement = mappedStatement(
                configuration,
                "test.selectEmptyProjection",
                SqlCommandType.SELECT,
                Map.class,
                "select id from user_account where id = ?",
                List.of(new ParameterMapping.Builder(configuration, "id", Long.class).build())
        );
        BoundSql boundSql = mappedStatement.getBoundSql(Map.of("id", 1L));
        TestResultSetHandler handler = new TestResultSetHandler(mappedStatement, boundSql, Collections.emptyList());

        Invocation invocation = new Invocation(
                handler,
                resultSetHandlerMethod("handleResultSets", Statement.class),
                new Object[]{null}
        );

        Object result = interceptor.intercept(invocation);

        assertSame(handler.result, result);
        assertEquals(0, decryptor.resolvePlanCalls);
        assertEquals(0, decryptor.decryptWithPlanCalls);
    }

    /**
     * 测试目的：验证 MyBatis 插件在 Executor 和 ResultSet 阶段的拦截顺序与职责边界。
     * 测试场景：模拟查询、更新和结果集处理调用，断言 SQL 改写、独立表预处理和结果解密只在正确阶段发生。
     */
    @Test
    void shouldPrepareSeparateTableReferencesBeforeExecutorUpdateRewrite() throws Throwable {
        Configuration configuration = new Configuration();
        RecordingSeparateTableEncryptionManager manager = new RecordingSeparateTableEncryptionManager();
        TestExecutor executor = new TestExecutor();
        DatabaseEncryptionInterceptor interceptor = interceptor(manager);
        MappedStatement mappedStatement = mappedStatement(
                configuration,
                "test.updateIdCard",
                SqlCommandType.UPDATE,
                EncryptedUser.class,
                "update user_account set id_card = ? where id = ?",
                List.of(
                        new ParameterMapping.Builder(configuration, "idCard", String.class).build(),
                        new ParameterMapping.Builder(configuration, "id", Long.class).build()
                )
        );
        EncryptedUser user = new EncryptedUser();
        user.setId(1L);
        user.setIdCard("320101199001011234");

        Invocation invocation = new Invocation(
                executor,
                executorMethod("update", MappedStatement.class, Object.class),
                new Object[]{mappedStatement, user}
        );

        interceptor.intercept(invocation);

        assertEquals(1, manager.prepareCalls);
        assertEquals("1001", executor.lastBoundSql.getAdditionalParameter("idCard"));
        assertNotSame(mappedStatement, executor.lastMappedStatement);
    }

    /**
     * 测试目的：验证 MyBatis 插件在 Executor 和 ResultSet 阶段的拦截顺序与职责边界。
     * 测试场景：模拟查询、更新和结果集处理调用，断言 SQL 改写、独立表预处理和结果解密只在正确阶段发生。
     */
    @Test
    void shouldPrepareSeparateTableReferencesForFieldLevelAnnotatedDtoBeforeExecutorUpdateRewrite() throws Throwable {
        Configuration configuration = new Configuration();
        RecordingSeparateTableEncryptionManager manager = new RecordingSeparateTableEncryptionManager();
        TestExecutor executor = new TestExecutor();
        DatabaseEncryptionInterceptor interceptor = interceptor(manager);
        MappedStatement mappedStatement = mappedStatement(
                configuration,
                "test.updateIdCardFieldLevel",
                SqlCommandType.UPDATE,
                FieldLevelEncryptedUser.class,
                "update user_account set id_card = ? where id = ?",
                List.of(
                        new ParameterMapping.Builder(configuration, "idCard", String.class).build(),
                        new ParameterMapping.Builder(configuration, "id", Long.class).build()
                )
        );
        FieldLevelEncryptedUser user = new FieldLevelEncryptedUser();
        user.setId(2L);
        user.setIdCard("320101199001015678");

        Invocation invocation = new Invocation(
                executor,
                executorMethod("update", MappedStatement.class, Object.class),
                new Object[]{mappedStatement, user}
        );

        interceptor.intercept(invocation);

        assertEquals(1, manager.prepareCalls);
        assertEquals("1001", executor.lastBoundSql.getAdditionalParameter("idCard"));
        assertNotSame(mappedStatement, executor.lastMappedStatement);
    }

    /**
     * 测试目的：验证标注 @SkipSqlRewrite 的 Mapper 方法在 Executor 阶段跳过 SQL 改写。
     * 测试场景：使用标注了 @SkipSqlRewrite 的接口方法名作为 statement id，
     * 断言 SQL 未被改写且独立表预处理未触发。
     */
    @Test
    void shouldBypassSqlRewriteWhenAnnotatedWithSkipSqlRewrite() throws Throwable {
        Configuration configuration = new Configuration();
        RecordingSeparateTableEncryptionManager manager = new RecordingSeparateTableEncryptionManager();
        TestExecutor executor = new TestExecutor();
        DatabaseEncryptionInterceptor interceptor = interceptor(manager);
        MappedStatement mappedStatement = mappedStatement(
                configuration,
                DatabaseEncryptionInterceptorTest.class.getName() + "$SkipTestMapper.skipMethod",
                SqlCommandType.SELECT,
                Map.class,
                "select id, phone from user_account where phone = ?",
                List.of(new ParameterMapping.Builder(configuration, "phone", String.class).build())
        );

        Invocation invocation = new Invocation(
                executor,
                executorMethod("query", MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class),
                new Object[]{mappedStatement, Map.of("phone", "13800138000"), RowBounds.DEFAULT, null}
        );

        interceptor.intercept(invocation);

        assertSame(mappedStatement, executor.lastMappedStatement);
        assertEquals(0, manager.prepareCalls);
        assertEquals("select id, phone from user_account where phone = ?", executor.lastBoundSql.getSql());
    }

    /**
     * 测试目的：验证标注 @SkipSqlRewrite 的 Mapper 方法在 ResultSet 阶段跳过结果解密。
     * 测试场景：使用标注了 @SkipSqlRewrite 的接口方法名作为 statement id，
     * 断言 resolvePlan 和 decrypt 均未调用。
     */
    @Test
    void shouldBypassResultDecryptionWhenAnnotatedWithSkipSqlRewrite() throws Throwable {
        Configuration configuration = new Configuration();
        RecordingResultDecryptor decryptor = recordingDecryptor();
        DatabaseEncryptionInterceptor interceptor = interceptor(null, decryptor);
        MappedStatement mappedStatement = mappedStatement(
                configuration,
                DatabaseEncryptionInterceptorTest.class.getName() + "$SkipTestMapper.skipMethod",
                SqlCommandType.SELECT,
                Map.class,
                "select id from user_account where id = ?",
                List.of(new ParameterMapping.Builder(configuration, "id", Long.class).build())
        );
        BoundSql boundSql = mappedStatement.getBoundSql(Map.of("id", 1L));
        TestResultSetHandler handler = new TestResultSetHandler(mappedStatement, boundSql, List.of(Map.of("id", 1L)));

        Invocation invocation = new Invocation(
                handler,
                resultSetHandlerMethod("handleResultSets", Statement.class),
                new Object[]{null}
        );

        Object result = interceptor.intercept(invocation);

        assertSame(handler.result, result);
        assertEquals(0, decryptor.resolvePlanCalls);
        assertEquals(0, decryptor.decryptWithPlanCalls);
    }

    /**
     * 测试目的：验证未标注 @SkipSqlRewrite 的方法仍正常经过 SQL 改写。
     * 测试场景：使用未标注的接口方法名作为 statement id，
     * 断言 SQL 仍被改写且独立表预处理正常触发。
     */
    @Test
    void shouldStillRewriteWhenNotAnnotatedWithSkipSqlRewrite() throws Throwable {
        Configuration configuration = new Configuration();
        RecordingSeparateTableEncryptionManager manager = new RecordingSeparateTableEncryptionManager();
        TestExecutor executor = new TestExecutor();
        DatabaseEncryptionInterceptor interceptor = interceptor(manager);
        MappedStatement mappedStatement = mappedStatement(
                configuration,
                DatabaseEncryptionInterceptorTest.class.getName() + "$SkipTestMapper.normalMethod",
                SqlCommandType.SELECT,
                Map.class,
                "select id, phone from user_account where phone = ?",
                List.of(new ParameterMapping.Builder(configuration, "phone", String.class).build())
        );

        Invocation invocation = new Invocation(
                executor,
                executorMethod("query", MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class),
                new Object[]{mappedStatement, Map.of("phone", "13800138000"), RowBounds.DEFAULT, null}
        );

        interceptor.intercept(invocation);

        assertNotSame(mappedStatement, executor.lastMappedStatement);
        assertTrue(executor.lastBoundSql.getSql().contains("phone_cipher"));
    }

    private DatabaseEncryptionInterceptor interceptor(SeparateTableEncryptionManager manager) {
        return interceptor(manager, null);
    }

    private DatabaseEncryptionInterceptor interceptor(SeparateTableEncryptionManager manager,
                                                      ResultDecryptor customDecryptor) {
        DatabaseEncryptionProperties properties = sampleProperties();
        EncryptMetadataRegistry metadataRegistry = new EncryptMetadataRegistry(
                properties, new AnnotationEncryptMetadataLoader());
        AlgorithmRegistry algorithmRegistry = sampleAlgorithms();
        SqlRewriteEngine sqlRewriteEngine = new SqlRewriteEngine(metadataRegistry, algorithmRegistry, properties);
        ResultDecryptor resultDecryptor = customDecryptor != null
                ? customDecryptor
                : new ResultDecryptor(metadataRegistry, algorithmRegistry, manager);
        return new DatabaseEncryptionInterceptor(sqlRewriteEngine, resultDecryptor, properties, manager,
                metadataRegistry);
    }

    private RecordingResultDecryptor recordingDecryptor() {
        DatabaseEncryptionProperties properties = sampleProperties();
        EncryptMetadataRegistry metadataRegistry = new EncryptMetadataRegistry(
                properties, new AnnotationEncryptMetadataLoader());
        return new RecordingResultDecryptor(metadataRegistry, sampleAlgorithms(), null);
    }

    private DatabaseEncryptionProperties sampleProperties() {
        DatabaseEncryptionProperties properties = new DatabaseEncryptionProperties();
        DatabaseEncryptionProperties.TableRuleProperties tableRule = new DatabaseEncryptionProperties.TableRuleProperties();
        tableRule.setTable("user_account");

        DatabaseEncryptionProperties.FieldRuleProperties phoneRule = new DatabaseEncryptionProperties.FieldRuleProperties();
        phoneRule.setProperty("phone");
        phoneRule.setColumn("phone");
        phoneRule.setStorageColumn("phone_cipher");
        phoneRule.setAssistedQueryColumn("phone_hash");
        phoneRule.setLikeQueryColumn("phone_like");
        tableRule.getFields().add(phoneRule);

        DatabaseEncryptionProperties.FieldRuleProperties idCardRule = new DatabaseEncryptionProperties.FieldRuleProperties();
        idCardRule.setProperty("idCard");
        idCardRule.setColumn("id_card");
        idCardRule.setStorageMode(FieldStorageMode.SEPARATE_TABLE);
        idCardRule.setStorageTable("user_id_card_encrypt");
        idCardRule.setStorageColumn("id_card_cipher");
        idCardRule.setStorageIdColumn("id");
        idCardRule.setAssistedQueryColumn("id_card_hash");
        idCardRule.setLikeQueryColumn("id_card_like");
        tableRule.getFields().add(idCardRule);

        properties.getTables().add(tableRule);
        properties.setDefaultCipherKey("unit-test-key");
        properties.setLogMaskedSql(false);
        return properties;
    }

    private AlgorithmRegistry sampleAlgorithms() {
        return new AlgorithmRegistry(
                Map.of("sm4", new Sm4CipherAlgorithm("unit-test-key")),
                Map.of("sm3", new Sm3AssistedQueryAlgorithm()),
                Map.of("normalizedLike", new NormalizedLikeQueryAlgorithm())
        );
    }

    private MappedStatement mappedStatement(Configuration configuration,
                                            String id,
                                            SqlCommandType commandType,
                                            Class<?> parameterType,
                                            String sql,
                                            List<ParameterMapping> parameterMappings) {
        SqlSource sqlSource = parameterObject -> new BoundSql(configuration, sql, parameterMappings, parameterObject);
        ParameterMap parameterMap = new ParameterMap.Builder(configuration, id + ".pm", parameterType, List.of()).build();
        ResultMap resultMap = new ResultMap.Builder(configuration, id + ".rm", Map.class, List.of()).build();
        return new MappedStatement.Builder(
                configuration, id, sqlSource, commandType)
                .resource("test")
                .parameterMap(parameterMap)
                .resultMaps(List.of(resultMap))
                .build();
    }

    private Method executorMethod(String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        return Executor.class.getMethod(name, parameterTypes);
    }

    private Method resultSetHandlerMethod(String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        return ResultSetHandler.class.getMethod(name, parameterTypes);
    }

    @EncryptTable("user_account")
    static class EncryptedUser {

        private Long id;

        @EncryptField(
                column = "id_card",
                storageMode = FieldStorageMode.SEPARATE_TABLE,
                storageTable = "user_id_card_encrypt",
                storageColumn = "id_card_cipher",
                storageIdColumn = "id",
                assistedQueryColumn = "id_card_hash",
                likeQueryColumn = "id_card_like"
        )
        private String idCard;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getIdCard() {
            return idCard;
        }

        public void setIdCard(String idCard) {
            this.idCard = idCard;
        }
    }

    static class FieldLevelEncryptedUser {

        private Long id;

        @EncryptField(
                table = "user_account",
                column = "id_card",
                storageMode = FieldStorageMode.SEPARATE_TABLE,
                storageTable = "user_id_card_encrypt",
                storageColumn = "id_card_cipher",
                storageIdColumn = "id",
                assistedQueryColumn = "id_card_hash",
                likeQueryColumn = "id_card_like"
        )
        private String idCard;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getIdCard() {
            return idCard;
        }

        public void setIdCard(String idCard) {
            this.idCard = idCard;
        }
    }

    static class RecordingSeparateTableEncryptionManager extends SeparateTableEncryptionManager {

        private int prepareCalls;

        RecordingSeparateTableEncryptionManager() {
            super(null, null, null, new DatabaseEncryptionProperties());
        }

        @Override
        public void prepareWriteReferences(MappedStatement mappedStatement, BoundSql boundSql) {
            prepareCalls++;
            boundSql.setAdditionalParameter("idCard", "1001");
        }
    }

    static class RecordingResultDecryptor extends ResultDecryptor {

        private int resolvePlanCalls;
        private int decryptWithPlanCalls;

        RecordingResultDecryptor(EncryptMetadataRegistry metadataRegistry,
                                 AlgorithmRegistry algorithmRegistry,
                                 SeparateTableEncryptionManager separateTableEncryptionManager) {
            super(metadataRegistry, algorithmRegistry, separateTableEncryptionManager);
        }

        @Override
        public io.github.jasper.mybatis.encrypt.core.decrypt.QueryResultPlan resolvePlan(MappedStatement mappedStatement,
                                                                                         BoundSql boundSql) {
            resolvePlanCalls++;
            return super.resolvePlan(mappedStatement, boundSql);
        }

        @Override
        public Object decrypt(Object resultObject, io.github.jasper.mybatis.encrypt.core.decrypt.QueryResultPlan queryResultPlan) {
            decryptWithPlanCalls++;
            return super.decrypt(resultObject, queryResultPlan);
        }
    }

    static class TestExecutor implements Executor {

        private MappedStatement lastMappedStatement;
        private BoundSql lastBoundSql;
        private Object lastParameterObject;

        @Override
        public int update(MappedStatement ms, Object parameter) {
            lastMappedStatement = ms;
            lastParameterObject = parameter;
            lastBoundSql = ms.getBoundSql(parameter);
            return 1;
        }

        @Override
        public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler) {
            lastMappedStatement = ms;
            lastParameterObject = parameter;
            lastBoundSql = ms.getBoundSql(parameter);
            return Collections.emptyList();
        }

        @Override
        public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler,
                                 CacheKey cacheKey, BoundSql boundSql) {
            lastMappedStatement = ms;
            lastParameterObject = parameter;
            lastBoundSql = boundSql;
            return Collections.emptyList();
        }

        @Override
        public <E> Cursor<E> queryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<BatchResult> flushStatements() {
            return Collections.emptyList();
        }

        @Override
        public void commit(boolean required) {
        }

        @Override
        public void rollback(boolean required) {
        }

        @Override
        public CacheKey createCacheKey(MappedStatement ms, Object parameterObject, RowBounds rowBounds,
                                       BoundSql boundSql) {
            return new CacheKey();
        }

        @Override
        public boolean isCached(MappedStatement ms, CacheKey key) {
            return false;
        }

        @Override
        public void clearLocalCache() {
        }

        @Override
        public void deferLoad(MappedStatement ms, MetaObject resultObject, String property, CacheKey key,
                              Class<?> targetType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Transaction getTransaction() {
            return new JdbcTransactionFactory().newTransaction(null, null, false);
        }

        @Override
        public void close(boolean forceRollback) {
        }

        @Override
        public boolean isClosed() {
            return false;
        }

        @Override
        public void setExecutorWrapper(Executor executor) {
        }
    }

    interface SkipTestMapper {

        @SkipSqlRewrite
        void skipMethod();

        void normalMethod();
    }

    static class TestResultSetHandler implements ResultSetHandler {

        private final MappedStatement mappedStatement;
        private final BoundSql boundSql;
        private final Object result;

        TestResultSetHandler(MappedStatement mappedStatement, BoundSql boundSql, Object result) {
            this.mappedStatement = mappedStatement;
            this.boundSql = boundSql;
            this.result = result;
        }

        public MappedStatement getMappedStatement() {
            return mappedStatement;
        }

        public BoundSql getBoundSql() {
            return boundSql;
        }

        @Override
        public <E> List<E> handleResultSets(Statement stmt) {
            @SuppressWarnings("unchecked")
            List<E> cast = (List<E>) result;
            return cast;
        }

        @Override
        public <E> Cursor<E> handleCursorResultSets(Statement stmt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void handleOutputParameters(CallableStatement cs) {
            throw new UnsupportedOperationException();
        }

    }
}
