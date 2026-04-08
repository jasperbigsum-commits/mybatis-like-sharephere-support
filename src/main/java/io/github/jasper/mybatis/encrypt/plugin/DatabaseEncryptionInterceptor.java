package io.github.jasper.mybatis.encrypt.plugin;

import java.sql.Connection;
import java.sql.Statement;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.resultset.ResultSetHandler;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.reflection.property.PropertyTokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.core.decrypt.ResultDecryptor;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptTableRule;
import io.github.jasper.mybatis.encrypt.core.rewrite.RewriteResult;
import io.github.jasper.mybatis.encrypt.core.rewrite.SqlRewriteEngine;
import io.github.jasper.mybatis.encrypt.core.support.SeparateTableEncryptionManager;

/**
 * MyBatis 插件入口。
 *
 * <p>负责在 SQL 执行前改写语句，在写操作后同步独立加密表，并在查询结果返回给业务代码前完成解密。</p>
 */
@Intercepts({
        @Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class}),
        @Signature(type = StatementHandler.class, method = "parameterize", args = {Statement.class}),
        @Signature(type = ResultSetHandler.class, method = "handleResultSets", args = {Statement.class}),
        @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class})
})
public class DatabaseEncryptionInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(DatabaseEncryptionInterceptor.class);
    private static final String REWRITE_APPLIED_FLAG = "__encrypt_rewrite_applied";
    private static final String SEPARATE_REFERENCE_PREPARED_FLAG = "__encrypt_separate_reference_prepared";

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
        if (target instanceof StatementHandler statementHandler) {
            if (shouldHandleStatementLifecycle(invocation)) {
                prepareSeparateTableReferences(statementHandler);
                rewriteSql(statementHandler);
            }
            return invocation.proceed();
        }
        if (target instanceof Executor && invocation.getArgs().length == 2 && invocation.getArgs()[0] instanceof MappedStatement) {
            return invocation.proceed();
        }
        if (target instanceof ResultSetHandler) {
            return resultDecryptor.decrypt(invocation.proceed());
        }
        return invocation.proceed();
    }

    private void rewriteSql(StatementHandler statementHandler) {
        BoundSql boundSql = statementHandler.getBoundSql();
        if (boundSql.hasAdditionalParameter(REWRITE_APPLIED_FLAG)) {
            return;
        }
        MappedStatement mappedStatement = resolveMappedStatement(statementHandler);
        if (mappedStatement == null) {
            return;
        }
        String originalSql = boundSql.getSql();
        List<ParameterMapping> originalParameterMappings = List.copyOf(boundSql.getParameterMappings());
        RewriteResult rewriteResult = sqlRewriteEngine.rewrite(mappedStatement, boundSql);
        if (!rewriteResult.changed()) {
            return;
        }
        rewriteResult.applyTo(boundSql);
        boundSql.setAdditionalParameter(REWRITE_APPLIED_FLAG, Boolean.TRUE);
        if (properties.isLogMaskedSql() && log.isDebugEnabled()) {
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

    private void prepareSeparateTableReferences(StatementHandler statementHandler) {
        if (separateTableEncryptionManager == null) {
            return;
        }
        BoundSql boundSql = statementHandler.getBoundSql();
        if (boundSql.hasAdditionalParameter(SEPARATE_REFERENCE_PREPARED_FLAG)) {
            return;
        }
        MappedStatement mappedStatement = resolveMappedStatement(statementHandler);
        if (!shouldPrepareSeparateTableReferences(mappedStatement, boundSql)) {
            return;
        }
        separateTableEncryptionManager.prepareWriteReferences(mappedStatement, boundSql);
        boundSql.setAdditionalParameter(SEPARATE_REFERENCE_PREPARED_FLAG, Boolean.TRUE);
    }

    private boolean shouldHandleStatementLifecycle(Invocation invocation) {
        String methodName = invocation.getMethod().getName();
        return "prepare".equals(methodName) || "parameterize".equals(methodName);
    }

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

    private String lastPropertyName(String property) {
        String name = new PropertyTokenizer(property).getName();
        int dotIndex = property.lastIndexOf('.');
        String leaf = dotIndex >= 0 ? property.substring(dotIndex + 1) : name;
        int bracketIndex = leaf.indexOf('[');
        return bracketIndex >= 0 ? leaf.substring(0, bracketIndex) : leaf;
    }

    private MappedStatement resolveMappedStatement(StatementHandler statementHandler) {
        MetaObject metaObject = SystemMetaObject.forObject(statementHandler);
        Object mappedStatement = read(metaObject, "delegate.mappedStatement");
        if (mappedStatement == null) {
            mappedStatement = read(metaObject, "mappedStatement");
        }
        return mappedStatement instanceof MappedStatement value ? value : null;
    }

    private Object read(MetaObject metaObject, String path) {
        return metaObject.hasGetter(path) ? metaObject.getValue(path) : null;
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(java.util.Properties properties) {
    }
}
