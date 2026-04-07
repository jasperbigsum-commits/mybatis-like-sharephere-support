package io.github.jasper.mybatis.encrypt.plugin;

import java.sql.Connection;
import java.sql.Statement;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.resultset.ResultSetHandler;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.core.decrypt.ResultDecryptor;
import io.github.jasper.mybatis.encrypt.core.rewrite.RewriteResult;
import io.github.jasper.mybatis.encrypt.core.rewrite.SqlRewriteEngine;
import io.github.jasper.mybatis.encrypt.core.support.SeparateTableEncryptionManager;

/**
 * MyBatis plugin entry point.
 *
 * <p>Rewrites SQL before execution, synchronizes separate encrypted tables after writes,
 * and decrypts query results before they are returned to business code.</p>
 */
@Intercepts({
        @Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class}),
        @Signature(type = ResultSetHandler.class, method = "handleResultSets", args = {Statement.class}),
        @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class})
})
public class DatabaseEncryptionInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(DatabaseEncryptionInterceptor.class);

    private final SqlRewriteEngine sqlRewriteEngine;
    private final ResultDecryptor resultDecryptor;
    private final DatabaseEncryptionProperties properties;
    private final SeparateTableEncryptionManager separateTableEncryptionManager;

    public DatabaseEncryptionInterceptor(SqlRewriteEngine sqlRewriteEngine,
                                         ResultDecryptor resultDecryptor,
                                         DatabaseEncryptionProperties properties,
                                         SeparateTableEncryptionManager separateTableEncryptionManager) {
        this.sqlRewriteEngine = sqlRewriteEngine;
        this.resultDecryptor = resultDecryptor;
        this.properties = properties;
        this.separateTableEncryptionManager = separateTableEncryptionManager;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        Object target = invocation.getTarget();
        if (target instanceof StatementHandler statementHandler) {
            rewriteSql(statementHandler);
            return invocation.proceed();
        }
        if (target instanceof Executor && invocation.getArgs().length == 2 && invocation.getArgs()[0] instanceof MappedStatement mappedStatement) {
            Object result = invocation.proceed();
            if (separateTableEncryptionManager != null) {
                separateTableEncryptionManager.synchronizeAfterWrite(mappedStatement, invocation.getArgs()[1]);
            }
            return result;
        }
        if (target instanceof ResultSetHandler) {
            return resultDecryptor.decrypt(invocation.proceed());
        }
        return invocation.proceed();
    }

    private void rewriteSql(StatementHandler statementHandler) {
        BoundSql boundSql = statementHandler.getBoundSql();
        MappedStatement mappedStatement = resolveMappedStatement(statementHandler);
        if (mappedStatement == null) {
            return;
        }
        RewriteResult rewriteResult = sqlRewriteEngine.rewrite(mappedStatement, boundSql);
        if (!rewriteResult.changed()) {
            return;
        }
        rewriteResult.applyTo(boundSql);
        if (properties.isLogMaskedSql() && log.isDebugEnabled()) {
            log.debug("Rewrote encrypted SQL: {}", rewriteResult.maskedSql());
        }
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
