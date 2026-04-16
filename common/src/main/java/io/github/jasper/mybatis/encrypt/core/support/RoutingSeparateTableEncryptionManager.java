package io.github.jasper.mybatis.encrypt.core.support;

import io.github.jasper.mybatis.encrypt.config.SqlDialectContextHolder;
import io.github.jasper.mybatis.encrypt.core.decrypt.QueryResultPlan;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 多数据源场景下按当前上下文路由的独立表加密管理器。
 */
public class RoutingSeparateTableEncryptionManager extends SeparateTableEncryptionManager {

    private final Map<String, SeparateTableEncryptionManager> managers;
    private final SeparateTableEncryptionManager defaultManager;

    /**
     * 创建路由管理器。
     *
     * @param managers 数据源名称与实际管理器映射
     */
    public RoutingSeparateTableEncryptionManager(Map<String, SeparateTableEncryptionManager> managers) {
        super(null, null, null, null, new NoOpSeparateTableRowPersister());
        this.managers = new LinkedHashMap<String, SeparateTableEncryptionManager>(managers);
        this.defaultManager = this.managers.isEmpty() ? null : this.managers.values().iterator().next();
    }

    @Override
    public void prepareWriteReferences(MappedStatement mappedStatement, BoundSql boundSql, Executor executor) {
        SeparateTableEncryptionManager manager = currentManager();
        if (manager != null) {
            manager.prepareWriteReferences(mappedStatement, boundSql, executor);
        }
    }

    @Override
    public void hydrateResults(Object resultObject) {
        SeparateTableEncryptionManager manager = currentManager();
        if (manager != null) {
            manager.hydrateResults(resultObject);
        }
    }

    @Override
    public void hydrateResults(Object resultObject, QueryResultPlan queryResultPlan) {
        SeparateTableEncryptionManager manager = currentManager();
        if (manager != null) {
            manager.hydrateResults(resultObject, queryResultPlan);
        }
    }

    @Override
    public void beginQueryScope() {
        SeparateTableEncryptionManager manager = currentManager();
        if (manager != null) {
            manager.beginQueryScope();
        }
    }

    @Override
    public void endQueryScope() {
        SeparateTableEncryptionManager manager = currentManager();
        if (manager != null) {
            manager.endQueryScope();
        }
    }

    private SeparateTableEncryptionManager currentManager() {
        String dataSourceName = SqlDialectContextHolder.currentDataSourceName();
        if (dataSourceName != null) {
            SeparateTableEncryptionManager manager = managers.get(dataSourceName);
            if (manager != null) {
                return manager;
            }
        }
        return defaultManager;
    }

    private static final class NoOpSeparateTableRowPersister implements SeparateTableRowPersister {

        @Override
        public void insert(SeparateTableInsertRequest request, MappedStatement sourceStatement, Executor executor) {
            throw new UnsupportedOperationException("No-op routing row persister should not be used directly.");
        }
    }
}
