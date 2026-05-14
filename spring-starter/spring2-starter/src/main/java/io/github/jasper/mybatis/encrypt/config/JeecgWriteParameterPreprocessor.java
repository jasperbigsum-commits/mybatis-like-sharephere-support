package io.github.jasper.mybatis.encrypt.config;

import io.github.jasper.mybatis.encrypt.plugin.WriteParameterPreprocessor;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.BatchResult;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

/**
 * Reflective adapter that reuses JEECG write interceptors as pre-BoundSql preprocessors.
 */
public class JeecgWriteParameterPreprocessor implements WriteParameterPreprocessor {

    private static final Method EXECUTOR_UPDATE_METHOD =
            ReflectionUtils.findMethod(Executor.class, "update", MappedStatement.class, Object.class);

    private final JeecgInterceptorResolver interceptorResolver;
    private volatile List<Interceptor> interceptors;

    /**
     * Creates a JEECG-compatible reflective preprocessor.
     *
     * @param beanFactory current Spring bean factory
     */
    public JeecgWriteParameterPreprocessor(BeanFactory beanFactory) {
        this.interceptorResolver = new JeecgInterceptorResolver(beanFactory);
    }

    @Override
    public void preprocess(MappedStatement mappedStatement, Object parameterObject) {
        List<Interceptor> resolvedInterceptors = getInterceptors();
        if (resolvedInterceptors.isEmpty() || mappedStatement == null || parameterObject == null
                || EXECUTOR_UPDATE_METHOD == null) {
            return;
        }
        Invocation invocation = new Invocation(NoOpExecutor.INSTANCE, EXECUTOR_UPDATE_METHOD,
                new Object[]{mappedStatement, parameterObject});
        for (Interceptor interceptor : resolvedInterceptors) {
            try {
                interceptor.intercept(invocation);
            } catch (Throwable ex) {
                throw new IllegalStateException("Failed to invoke JEECG write interceptor: "
                        + interceptor.getClass().getName(), ex);
            }
        }
    }

    private List<Interceptor> getInterceptors() {
        List<Interceptor> current = interceptors;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            current = interceptors;
            if (current == null) {
                current = Collections.unmodifiableList(interceptorResolver.resolve());
                interceptors = current;
            }
            return current;
        }
    }

    private enum NoOpExecutor implements Executor {

        INSTANCE;

        @Override
        public int update(MappedStatement ms, Object parameter) {
            return 0;
        }

        @Override
        public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds,
                                 ResultHandler resultHandler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds,
                                 ResultHandler resultHandler, CacheKey cacheKey, BoundSql boundSql) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <E> Cursor<E> queryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<BatchResult> flushStatements() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void commit(boolean required) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void rollback(boolean required) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CacheKey createCacheKey(MappedStatement ms, Object parameterObject, RowBounds rowBounds,
                                       BoundSql boundSql) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isCached(MappedStatement ms, CacheKey key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clearLocalCache() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deferLoad(MappedStatement ms, MetaObject resultObject, String property, CacheKey key,
                              Class<?> targetType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Transaction getTransaction() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close(boolean forceRollback) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isClosed() {
            return false;
        }

        @Override
        public void setExecutorWrapper(Executor executor) {
        }
    }
}
