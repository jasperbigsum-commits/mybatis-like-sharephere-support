package io.github.jasper.mybatis.encrypt.plugin;

import io.github.jasper.mybatis.encrypt.annotation.SkipSqlRewrite;
import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.config.SqlDialectContextHolder;
import io.github.jasper.mybatis.encrypt.core.decrypt.QueryResultPlan;
import io.github.jasper.mybatis.encrypt.core.decrypt.ResultDecryptor;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptTableRule;
import io.github.jasper.mybatis.encrypt.core.rewrite.ParameterValueResolver;
import io.github.jasper.mybatis.encrypt.core.rewrite.RewriteResult;
import io.github.jasper.mybatis.encrypt.core.rewrite.SqlRewriteEngine;
import io.github.jasper.mybatis.encrypt.core.support.DataSourceNameResolver;
import io.github.jasper.mybatis.encrypt.core.support.DefaultSeparateTableRowPersister;
import io.github.jasper.mybatis.encrypt.core.support.SeparateTableEncryptionManager;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.resultset.ResultSetHandler;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.reflection.property.PropertyTokenizer;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Main MyBatis plugin entry point for SQL rewrite and result decryption.
 *
 * <p>The interceptor performs write-side preprocessing and SQL rewrite at the
 * {@link Executor} layer, then applies separate-table hydration and decryption
 * when {@link ResultSetHandler} returns query results.</p>
 */
@Intercepts({
        @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}),
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class, CacheKey.class,
                        BoundSql.class}),
        @Signature(type = ResultSetHandler.class, method = "handleResultSets", args = {Statement.class})
})
public class DatabaseEncryptionInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(DatabaseEncryptionInterceptor.class);

    private final SqlRewriteEngine sqlRewriteEngine;
    private final ResultDecryptor resultDecryptor;
    private final DatabaseEncryptionProperties properties;
    private final SeparateTableEncryptionManager separateTableEncryptionManager;
    private final EncryptMetadataRegistry metadataRegistry;
    private final DataSourceNameResolver dataSourceNameResolver;
    private final WriteParameterPreprocessor writeParameterPreprocessor;

    /**
     * Creates the interceptor with all supported collaborators.
     *
     * @param sqlRewriteEngine SQL rewrite engine
     * @param resultDecryptor query result decryptor
     * @param properties plugin properties
     * @param separateTableEncryptionManager separate-table manager
     * @param metadataRegistry encryption metadata registry
     * @param dataSourceNameResolver data source name resolver
     * @param writeParameterPreprocessor write-parameter preprocessor invoked before BoundSql creation
     */
    public DatabaseEncryptionInterceptor(SqlRewriteEngine sqlRewriteEngine,
                                         ResultDecryptor resultDecryptor,
                                         DatabaseEncryptionProperties properties,
                                         SeparateTableEncryptionManager separateTableEncryptionManager,
                                         EncryptMetadataRegistry metadataRegistry,
                                         DataSourceNameResolver dataSourceNameResolver,
                                         WriteParameterPreprocessor writeParameterPreprocessor) {
        this.sqlRewriteEngine = sqlRewriteEngine;
        this.resultDecryptor = resultDecryptor;
        this.properties = properties;
        this.separateTableEncryptionManager = separateTableEncryptionManager;
        this.metadataRegistry = metadataRegistry;
        this.dataSourceNameResolver = dataSourceNameResolver;
        this.writeParameterPreprocessor = writeParameterPreprocessor;
    }

    /**
     * Creates the interceptor without a data source resolver or write preprocessor.
     *
     * @param sqlRewriteEngine SQL rewrite engine
     * @param resultDecryptor query result decryptor
     * @param properties plugin properties
     * @param separateTableEncryptionManager separate-table manager
     * @param metadataRegistry encryption metadata registry
     */
    public DatabaseEncryptionInterceptor(SqlRewriteEngine sqlRewriteEngine,
                                         ResultDecryptor resultDecryptor,
                                         DatabaseEncryptionProperties properties,
                                         SeparateTableEncryptionManager separateTableEncryptionManager,
                                         EncryptMetadataRegistry metadataRegistry) {
        this(sqlRewriteEngine, resultDecryptor, properties, separateTableEncryptionManager, metadataRegistry, null,
                null);
    }

    /**
     * Creates the interceptor without a write preprocessor.
     *
     * @param sqlRewriteEngine SQL rewrite engine
     * @param resultDecryptor query result decryptor
     * @param properties plugin properties
     * @param separateTableEncryptionManager separate-table manager
     * @param metadataRegistry encryption metadata registry
     * @param dataSourceNameResolver data source name resolver
     */
    public DatabaseEncryptionInterceptor(SqlRewriteEngine sqlRewriteEngine,
                                         ResultDecryptor resultDecryptor,
                                         DatabaseEncryptionProperties properties,
                                         SeparateTableEncryptionManager separateTableEncryptionManager,
                                         EncryptMetadataRegistry metadataRegistry,
                                         DataSourceNameResolver dataSourceNameResolver) {
        this(sqlRewriteEngine, resultDecryptor, properties, separateTableEncryptionManager, metadataRegistry,
                dataSourceNameResolver, null);
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        Object target = invocation.getTarget();
        if (target instanceof Executor) {
            return interceptExecutor(invocation);
        }
        if (target instanceof ResultSetHandler) {
            return interceptResultSet(invocation);
        }
        return invocation.proceed();
    }

    /**
     * Handles write-side preprocessing and SQL rewrite at the executor layer.
     *
     * <p>The implementation uses public MyBatis method arguments instead of
     * depending on {@code StatementHandler} internals. For the six-argument
     * {@code query(...)} form it reuses the supplied {@link BoundSql} so the
     * cache key and execution SQL stay aligned.</p>
     *
     * @param invocation executor invocation
     * @return invocation result
     * @throws Throwable executor failure
     */
    private Object interceptExecutor(Invocation invocation) throws Throwable {
        Object[] args = invocation.getArgs();
        if (!(args[0] instanceof MappedStatement)) {
            return invocation.proceed();
        }
        MappedStatement mappedStatement = (MappedStatement) args[0];
        if (DefaultSeparateTableRowPersister.isManagedStatementId(mappedStatement.getId())) {
            return invocation.proceed();
        }
        if (isSkipSqlRewrite(mappedStatement)) {
            return invocation.proceed();
        }
        try (SqlDialectContextHolder.Scope ignored = SqlDialectContextHolder.open(resolveDataSourceName(mappedStatement))) {
            Executor executor = (Executor) invocation.getTarget();
            Object parameterObject = args.length > 1 ? args[1] : null;
            preprocessWriteParameters(mappedStatement, parameterObject);
            BoundSql boundSql = resolveBoundSql(mappedStatement, parameterObject, args);
            MappedStatement rewrittenStatement = rewriteMappedStatement(executor, mappedStatement, boundSql);
            if (rewrittenStatement != mappedStatement) {
                args[0] = rewrittenStatement;
                if (args.length == 6) {
                    args[5] = boundSql;
                }
            }
            return invocation.proceed();
        }
    }

    private Object interceptResultSet(Invocation invocation) throws Throwable {
        MappedStatement mappedStatement = resolveResultSetMappedStatement(invocation.getTarget());
        BoundSql boundSql = resolveResultSetBoundSql(invocation.getTarget());
        if (mappedStatement != null && isSkipSqlRewrite(mappedStatement)) {
            return invocation.proceed();
        }
        try (SqlDialectContextHolder.Scope ignored = SqlDialectContextHolder.open(resolveDataSourceName(mappedStatement))) {
            Object result = invocation.proceed();
            if (mappedStatement == null || shouldBypassResultDecryption(result)) {
                return result;
            }
            QueryResultPlan queryResultPlan = resultDecryptor.resolvePlan(mappedStatement, boundSql);
            return resultDecryptor.decrypt(result, queryResultPlan);
        }
    }

    private boolean shouldBypassResultDecryption(Object result) {
        if (result == null) {
            return true;
        }
        if (result instanceof Collection<?>) {
            return ((Collection<?>) result).isEmpty();
        }
        return result.getClass().isArray() && java.lang.reflect.Array.getLength(result) == 0;
    }

    /**
     * Resolves the BoundSql to use for the current invocation.
     *
     * <p>Regular four-argument queries and updates obtain {@link BoundSql} from
     * {@link MappedStatement#getBoundSql(Object)}. The six-argument query form
     * must reuse the caller-provided {@link BoundSql} to preserve cache-key and
     * parameter-order consistency.</p>
     *
     * @param mappedStatement current mapped statement
     * @param parameterObject runtime parameter object
     * @param args executor method arguments
     * @return bound SQL for the invocation
     */
    private BoundSql resolveBoundSql(MappedStatement mappedStatement, Object parameterObject, Object[] args) {
        if (args.length == 6 && args[5] instanceof BoundSql) {
            return (BoundSql) args[5];
        }
        return mappedStatement.getBoundSql(parameterObject);
    }

    /**
     * Runs write-parameter preprocessing before BoundSql is materialized.
     *
     * <p>This hook only applies to external business {@code INSERT}/{@code UPDATE}
     * calls. It exists to integrate with interceptors such as JEECG that mutate
     * audit fields in-place during {@code Executor.update(...)} processing.
     * Managed internal statements are filtered out earlier and do not execute this path.</p>
     *
     * @param mappedStatement current mapped statement
     * @param parameterObject runtime parameter object
     */
    private void preprocessWriteParameters(MappedStatement mappedStatement, Object parameterObject) {
        if (writeParameterPreprocessor == null || mappedStatement == null || parameterObject == null) {
            return;
        }
        SqlCommandType commandType = mappedStatement.getSqlCommandType();
        if (commandType != SqlCommandType.INSERT && commandType != SqlCommandType.UPDATE) {
            return;
        }
        writeParameterPreprocessor.preprocess(mappedStatement, parameterObject);
    }

    private MappedStatement resolveResultSetMappedStatement(Object target) {
        if (target == null) {
            return null;
        }
        // Each SystemMetaObject.forObject(...) call wraps only the current target.
        // There is no cross-invocation shared state here.
        MetaObject metaObject = SystemMetaObject.forObject(target);
        if (metaObject.hasGetter("mappedStatement")) {
            Object mappedStatement = metaObject.getValue("mappedStatement");
            if (mappedStatement instanceof MappedStatement) {
                return (MappedStatement) mappedStatement;
            }
        }
        if (metaObject.hasGetter("delegate.mappedStatement")) {
            Object mappedStatement = metaObject.getValue("delegate.mappedStatement");
            if (mappedStatement instanceof MappedStatement) {
                return (MappedStatement) mappedStatement;
            }
        }
        return null;
    }

    private BoundSql resolveResultSetBoundSql(Object target) {
        if (target == null) {
            return null;
        }
        // MyBatis versions may expose boundSql directly or through delegate.
        // Both lookups read only the current invocation object graph and are not cached.
        MetaObject metaObject = SystemMetaObject.forObject(target);
        if (metaObject.hasGetter("boundSql")) {
            Object boundSql = metaObject.getValue("boundSql");
            if (boundSql instanceof BoundSql) {
                return (BoundSql) boundSql;
            }
        }
        if (metaObject.hasGetter("delegate.boundSql")) {
            Object boundSql = metaObject.getValue("delegate.boundSql");
            if (boundSql instanceof BoundSql) {
                return (BoundSql) boundSql;
            }
        }
        return null;
    }

    /**
     * Rewrites the mapped statement when SQL text or bound parameters changed.
     *
     * <p>A new {@link MappedStatement} is created only when SQL rewrite changed
     * the statement text or separate-table preparation injected additional
     * parameters into {@link BoundSql}.</p>
     *
     * @param mappedStatement original mapped statement
     * @param boundSql current bound SQL
     * @return mapped statement to continue execution with
     */
    private MappedStatement rewriteMappedStatement(Executor executor, MappedStatement mappedStatement, BoundSql boundSql) {
        prepareSeparateTableReferences(executor, mappedStatement, boundSql);
        String originalSql = boundSql.getSql();
        List<ParameterMapping> originalParameterMappings = new ArrayList<ParameterMapping>(boundSql.getParameterMappings());
        RewriteResult rewriteResult = sqlRewriteEngine.rewrite(mappedStatement, boundSql);
        if (!rewriteResult.changed()
                && !boundSql.hasAdditionalParameter(ParameterValueResolver.PREPARED_REFERENCE_PARAMETER)) {
            return mappedStatement;
        }
        if (rewriteResult.changed()) {
            rewriteResult.applyTo(boundSql);
            logRewriteDetail(mappedStatement, originalSql, originalParameterMappings, boundSql, rewriteResult);
        }
        return copyMappedStatement(mappedStatement, new StaticBoundSqlSqlSource(boundSql));
    }

    /**
     * Emits structured debug logging for SQL rewrite details.
     *
     * @param mappedStatement current mapped statement
     * @param originalSql SQL before rewrite
     * @param originalParameterMappings parameter mappings before rewrite
     * @param boundSql rewritten bound SQL
     * @param rewriteResult rewrite result
     */
    private void logRewriteDetail(MappedStatement mappedStatement,
                                  String originalSql,
                                  List<ParameterMapping> originalParameterMappings,
                                  BoundSql boundSql,
                                  RewriteResult rewriteResult) {
        if (!properties.isLogMaskedSql() || !log.isDebugEnabled()) {
            return;
        }
        log.debug("Encrypted SQL rewrite detail:\n"
                        + "statementId: {}\n"
                        + "commandType: {}\n"
                        + "originalSql: {}\n"
                        + "rewrittenMaskedSql: {}\n"
                        + "originalParameterMappings: {}\n"
                        + "rewrittenParameterMappings: {}\n"
                        + "maskedParameters: {}",
                mappedStatement.getId(),
                mappedStatement.getSqlCommandType(),
                singleLine(originalSql),
                singleLine(rewriteResult.maskedSql()),
                describeParameterMappings(originalParameterMappings),
                describeParameterMappings(boundSql.getParameterMappings()),
                rewriteResult.maskedParameters());
    }

    private String describeParameterMappings(List<ParameterMapping> parameterMappings) {
        return parameterMappings.stream()
                .map(mapping -> {
                    Class<?> javaType = mapping.getJavaType();
                    String typeName = javaType == null ? "unknown" : javaType.getSimpleName();
                    return mapping.getProperty() + ":" + typeName;
                })
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private String singleLine(String sql) {
        if (sql == null) {
            return "<null>";
        }
        return sql.replaceAll("\\s+", " ").trim();
    }

    /**
     * Prepares separate-table reference identifiers before write execution.
     *
     * @param mappedStatement current mapped statement
     * @param boundSql current bound SQL
     */
    private void prepareSeparateTableReferences(Executor executor, MappedStatement mappedStatement, BoundSql boundSql) {
        if (separateTableEncryptionManager == null) {
            return;
        }
        if (!shouldPrepareSeparateTableReferences(mappedStatement, boundSql)) {
            return;
        }
        separateTableEncryptionManager.prepareWriteReferences(mappedStatement, boundSql, executor);
    }

    /**
     * Determines whether separate-table write preparation is required.
     *
     * @param mappedStatement current mapped statement
     * @param boundSql current bound SQL
     * @return {@code true} when separate-table references should be prepared
     */
    private boolean shouldPrepareSeparateTableReferences(MappedStatement mappedStatement, BoundSql boundSql) {
        if (mappedStatement == null || metadataRegistry == null) {
            return false;
        }
        SqlCommandType commandType = mappedStatement.getSqlCommandType();
        if (commandType != SqlCommandType.INSERT && commandType != SqlCommandType.UPDATE) {
            return false;
        }
        metadataRegistry.warmUp(mappedStatement, boundSql.getParameterObject());
        Object parameterObject = boundSql.getParameterObject();
        if (parameterObject == null) {
            return false;
        }
        return hasSeparateTableRule(parameterObject, boundSql);
    }

    /**
     * Recursively checks whether runtime parameters contain separate-table fields.
     *
     * @param candidate current parameter node
     * @param boundSql current bound SQL
     * @return {@code true} when a separate-table encrypted field participates in the statement
     */
    private boolean hasSeparateTableRule(Object candidate, BoundSql boundSql) {
        if (candidate == null) {
            return false;
        }
        if (candidate instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) candidate;
            return map.values().stream().anyMatch(value -> hasSeparateTableRule(value, boundSql));
        }
        if (candidate instanceof Iterable<?>) {
            Iterable<?> iterable = (Iterable<?>) candidate;
            for (Object value : iterable) {
                if (hasSeparateTableRule(value, boundSql)) {
                    return true;
                }
            }
            return false;
        }
        if (candidate.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(candidate);
            for (int index = 0; index < length; index++) {
                if (hasSeparateTableRule(java.lang.reflect.Array.get(candidate, index), boundSql)) {
                    return true;
                }
            }
            return false;
        }
        EncryptTableRule tableRule = metadataRegistry.findByEntity(candidate.getClass()).orElse(null);
        if (tableRule == null) {
            return false;
        }
        MetaObject metaObject = SystemMetaObject.forObject(candidate);
        for (EncryptColumnRule rule : tableRule.getColumnRules()) {
            if (!rule.isStoredInSeparateTable() || !metaObject.hasGetter(rule.property())) {
                continue;
            }
            Object value = metaObject.getValue(rule.property());
            // Prepare references only when the property participates in the current SQL binding.
            if (value != null && isMappedProperty(boundSql, rule.property())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether a logical property participates in the current SQL binding.
     *
     * @param boundSql current bound SQL
     * @param property logical property name
     * @return {@code true} when the current SQL binds the property
     */
    private boolean isMappedProperty(BoundSql boundSql, String property) {
        for (ParameterMapping parameterMapping : boundSql.getParameterMappings()) {
            String parameterProperty = parameterMapping.getProperty();
            if (parameterProperty == null) {
                continue;
            }
            if (property.equals(parameterProperty) || property.equals(lastPropertyName(parameterProperty))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Extracts the leaf property name from a MyBatis parameter path.
     *
     * <p>For example, {@code list[0].idCard} becomes {@code idCard}, which lets
     * collection parameter bindings match entity field rules.</p>
     *
     * @param property MyBatis parameter path
     * @return leaf property name
     */
    private String lastPropertyName(String property) {
        String name = new PropertyTokenizer(property).getName();
        int dotIndex = property.lastIndexOf('.');
        String leaf = dotIndex >= 0 ? property.substring(dotIndex + 1) : name;
        int bracketIndex = leaf.indexOf('[');
        return bracketIndex >= 0 ? leaf.substring(0, bracketIndex) : leaf;
    }

    private String resolveDataSourceName(MappedStatement mappedStatement) {
        return dataSourceNameResolver == null ? null : dataSourceNameResolver.resolve(mappedStatement);
    }

    /**
     * Checks whether the mapper method is annotated with {@link SkipSqlRewrite}.
     *
     * @param mappedStatement current mapped statement
     * @return {@code true} when the mapper method declares {@code @SkipSqlRewrite}
     */
    private boolean isSkipSqlRewrite(MappedStatement mappedStatement) {
        String statementId = mappedStatement.getId();
        if (statementId == null || statementId.isEmpty()) {
            return false;
        }
        int separator = statementId.lastIndexOf('.');
        if (separator <= 0 || separator >= statementId.length() - 1) {
            return false;
        }
        String mapperClassName = statementId.substring(0, separator);
        String methodName = statementId.substring(separator + 1);
        try {
            Class<?> mapperType = Class.forName(mapperClassName);
            for (Method method : mapperType.getMethods()) {
                if (methodName.equals(method.getName())
                        && method.isAnnotationPresent(SkipSqlRewrite.class)) {
                    return true;
                }
            }
        } catch (ClassNotFoundException | LinkageError ignore) {
        }
        return false;
    }

    /**
     * Copies a mapped statement while replacing only its {@link SqlSource}.
     *
     * <p>The copied statement preserves caches, timeout settings, key generators,
     * result mappings, and related metadata so SQL rewrite does not accidentally
     * change execution semantics.</p>
     *
     * @param original original mapped statement
     * @param sqlSource SQL source that returns the current bound SQL
     * @return wrapped mapped statement
     */
    private MappedStatement copyMappedStatement(MappedStatement original, SqlSource sqlSource) {
        MappedStatement.Builder builder = new MappedStatement.Builder(
                original.getConfiguration(), original.getId(), sqlSource, original.getSqlCommandType())
                .resource(original.getResource())
                .fetchSize(original.getFetchSize())
                .timeout(original.getTimeout())
                .statementType(original.getStatementType())
                .resultSetType(original.getResultSetType())
                .parameterMap(original.getParameterMap())
                .resultMaps(original.getResultMaps())
                .cache(original.getCache())
                .flushCacheRequired(original.isFlushCacheRequired())
                .useCache(original.isUseCache())
                .resultOrdered(original.isResultOrdered())
                .databaseId(original.getDatabaseId())
                .lang(original.getLang())
                .keyGenerator(original.getKeyGenerator());
        if (original.getKeyProperties() != null && original.getKeyProperties().length > 0) {
            builder.keyProperty(String.join(",", original.getKeyProperties()));
        }
        if (original.getKeyColumns() != null && original.getKeyColumns().length > 0) {
            builder.keyColumn(String.join(",", original.getKeyColumns()));
        }
        if (original.getResultSets() != null && original.getResultSets().length > 0) {
            builder.resultSets(String.join(",", original.getResultSets()));
        }
        return builder.build();
    }

    /**
     * {@link SqlSource} implementation that always returns the same {@link BoundSql}.
     *
     * <p>Once rewrite has already rebuilt SQL text and parameter mappings at the
     * executor layer, downstream execution should consume that exact object instead
     * of materializing a fresh {@link BoundSql} from the original source.</p>
     */
    private static final class StaticBoundSqlSqlSource implements SqlSource {

        private final BoundSql boundSql;

        private StaticBoundSqlSqlSource(BoundSql boundSql) {
            this.boundSql = boundSql;
        }

        @Override
        public BoundSql getBoundSql(Object parameterObject) {
            return boundSql;
        }
    }
}
