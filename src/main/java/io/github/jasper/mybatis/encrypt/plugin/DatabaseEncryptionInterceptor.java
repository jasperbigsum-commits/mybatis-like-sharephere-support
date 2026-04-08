package io.github.jasper.mybatis.encrypt.plugin;

import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.resultset.ResultSetHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.reflection.property.PropertyTokenizer;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.core.decrypt.ResultDecryptor;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptTableRule;
import io.github.jasper.mybatis.encrypt.core.rewrite.ParameterValueResolver;
import io.github.jasper.mybatis.encrypt.core.rewrite.RewriteResult;
import io.github.jasper.mybatis.encrypt.core.rewrite.SqlRewriteEngine;
import io.github.jasper.mybatis.encrypt.core.support.SeparateTableEncryptionManager;

/**
 * MyBatis 插件入口。
 *
 * <p>负责在 Executor 层完成写前准备与 SQL 改写，在查询结果返回给业务代码前完成解密。</p>
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

    /**
     * 创建 MyBatis 加密拦截器。
     *
     * @param sqlRewriteEngine SQL 改写引擎
     * @param resultDecryptor 查询结果解密器
     * @param properties 插件配置属性
     * @param separateTableEncryptionManager 独立表加密管理器
     * @param metadataRegistry 加密元数据注册中心
     */
    public DatabaseEncryptionInterceptor(SqlRewriteEngine sqlRewriteEngine,
                                         ResultDecryptor resultDecryptor,
                                         DatabaseEncryptionProperties properties,
                                         SeparateTableEncryptionManager separateTableEncryptionManager,
                                         EncryptMetadataRegistry metadataRegistry) {
        this.sqlRewriteEngine = sqlRewriteEngine;
        this.resultDecryptor = resultDecryptor;
        this.properties = properties;
        this.separateTableEncryptionManager = separateTableEncryptionManager;
        this.metadataRegistry = metadataRegistry;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        Object target = invocation.getTarget();
        if (target instanceof Executor) {
            return interceptExecutor(invocation);
        }
        if (target instanceof ResultSetHandler) {
            return resultDecryptor.decrypt(invocation.proceed());
        }
        return invocation.proceed();
    }

    /**
     * 在 Executor 层统一处理写前准备和 SQL 改写。
     *
     * <p>这里直接使用 MyBatis 公开方法参数中的 {@link MappedStatement}，避免再依赖
     * {@code StatementHandler} 内部实现细节。对于六参 query 形态，优先复用调用链
     * 已经创建好的 {@link BoundSql}，保持缓存键与执行 SQL 一致。</p>
     *
     * @param invocation Executor 调用上下文
     * @return 原始调用结果
     * @throws Throwable 底层执行异常
     */
    private Object interceptExecutor(Invocation invocation) throws Throwable {
        Object[] args = invocation.getArgs();
        if (!(args[0] instanceof MappedStatement mappedStatement)) {
            return invocation.proceed();
        }
        Object parameterObject = args.length > 1 ? args[1] : null;
        BoundSql boundSql = resolveBoundSql(mappedStatement, parameterObject, args);
        MappedStatement rewrittenStatement = rewriteMappedStatement(mappedStatement, boundSql);
        if (rewrittenStatement != mappedStatement) {
            args[0] = rewrittenStatement;
            if (args.length == 6) {
                args[5] = boundSql;
            }
        }
        return invocation.proceed();
    }

    /**
     * 解析当前执行链应使用的 BoundSql。
     *
     * <p>普通四参 query 和 update 由 {@link MappedStatement#getBoundSql(Object)} 动态生成；
     * 六参 query 则必须复用调用方传入的 BoundSql，否则会打破上游已经构建好的缓存键与参数顺序。</p>
     *
     * @param mappedStatement 当前 mapped statement
     * @param parameterObject 运行时参数对象
     * @param args Executor 方法参数
     * @return 本次执行应使用的 BoundSql
     */
    private BoundSql resolveBoundSql(MappedStatement mappedStatement, Object parameterObject, Object[] args) {
        if (args.length == 6 && args[5] instanceof BoundSql provided) {
            return provided;
        }
        return mappedStatement.getBoundSql(parameterObject);
    }

    /**
     * 根据改写结果决定是否生成新的 MappedStatement。
     *
     * <p>只有在 SQL 本身被改写，或者独立表写前准备已经向 BoundSql 注入了额外参数时，
     * 才需要包装一个新的 SqlSource，把当前 BoundSql 固定下来交给后续执行链。</p>
     *
     * @param mappedStatement 原始 mapped statement
     * @param boundSql 当前 BoundSql
     * @return 可直接继续执行的 mapped statement
     */
    private MappedStatement rewriteMappedStatement(MappedStatement mappedStatement, BoundSql boundSql) {
        prepareSeparateTableReferences(mappedStatement, boundSql);
        String originalSql = boundSql.getSql();
        List<ParameterMapping> originalParameterMappings = List.copyOf(boundSql.getParameterMappings());
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
     * 输出结构化的 SQL 改写 debug 日志。
     *
     * @param mappedStatement 当前 mapped statement
     * @param originalSql 改写前 SQL
     * @param originalParameterMappings 改写前参数映射
     * @param boundSql 改写后的 BoundSql
     * @param rewriteResult 改写结果
     */
    private void logRewriteDetail(MappedStatement mappedStatement,
                                  String originalSql,
                                  List<ParameterMapping> originalParameterMappings,
                                  BoundSql boundSql,
                                  RewriteResult rewriteResult) {
        if (!properties.isLogMaskedSql() || !log.isDebugEnabled()) {
            return;
        }
        log.debug("""
                Encrypted SQL rewrite detail:
                statementId: {}
                commandType: {}
                originalSql: {}
                rewrittenMaskedSql: {}
                originalParameterMappings: {}
                rewrittenParameterMappings: {}
                maskedParameters: {}
                """,
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
     * 在 INSERT / UPDATE 执行前准备独立表引用 id。
     *
     * @param mappedStatement 当前 mapped statement
     * @param boundSql 当前 BoundSql
     */
    private void prepareSeparateTableReferences(MappedStatement mappedStatement, BoundSql boundSql) {
        if (separateTableEncryptionManager == null) {
            return;
        }
        if (!shouldPrepareSeparateTableReferences(mappedStatement, boundSql)) {
            return;
        }
        separateTableEncryptionManager.prepareWriteReferences(mappedStatement, boundSql);
    }

    /**
     * 判断本次执行是否需要进入独立表写前准备流程。
     *
     * @param mappedStatement 当前 mapped statement
     * @param boundSql 当前 BoundSql
     * @return 需要准备独立表引用时返回 {@code true}
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
     * 递归判断运行时参数中是否包含独立表加密字段。
     *
     * @param candidate 当前待检查参数节点
     * @param boundSql 当前 BoundSql
     * @return 命中独立表加密字段时返回 {@code true}
     */
    private boolean hasSeparateTableRule(Object candidate, BoundSql boundSql) {
        if (candidate == null) {
            return false;
        }
        if (candidate instanceof Map<?, ?> map) {
            return map.values().stream().anyMatch(value -> hasSeparateTableRule(value, boundSql));
        }
        if (candidate instanceof Iterable<?> iterable) {
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
            if (value != null && isMappedProperty(boundSql, rule.property())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断某个逻辑属性是否真实参与了本次 SQL 参数绑定。
     *
     * @param boundSql 当前 BoundSql
     * @param property 逻辑属性名
     * @return 当前 SQL 包含该属性绑定时返回 {@code true}
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
     * 提取参数路径中的叶子属性名。
     *
     * <p>例如 {@code list[0].idCard} 会被归一成 {@code idCard}，便于把集合参数映射
     * 与实体字段规则做匹配。</p>
     *
     * @param property MyBatis 参数路径
     * @return 叶子属性名
     */
    private String lastPropertyName(String property) {
        String name = new PropertyTokenizer(property).getName();
        int dotIndex = property.lastIndexOf('.');
        String leaf = dotIndex >= 0 ? property.substring(dotIndex + 1) : name;
        int bracketIndex = leaf.indexOf('[');
        return bracketIndex >= 0 ? leaf.substring(0, bracketIndex) : leaf;
    }

    /**
     * 复制一个仅替换 SqlSource 的 MappedStatement。
     *
     * <p>这里保留原 statement 的缓存、超时、键生成器、结果映射等元数据，
     * 避免 SQL 改写引入执行语义变化。</p>
     *
     * @param original 原始 mapped statement
     * @param sqlSource 固定返回当前 BoundSql 的 SqlSource
     * @return 包装后的 mapped statement
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

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(java.util.Properties properties) {
    }

    /**
     * 固定返回同一个 BoundSql 的 SqlSource。
     *
     * <p>Executor 层已经完成 SQL 改写和参数映射重建，后续链路应直接消费当前对象，
     * 不再重新根据原始 SqlSource 生成新的 BoundSql。</p>
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
