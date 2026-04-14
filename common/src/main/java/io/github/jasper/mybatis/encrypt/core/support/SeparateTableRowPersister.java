package io.github.jasper.mybatis.encrypt.core.support;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;

/**
 * 独立加密表写入执行器。
 *
 * <p>优先复用当前 MyBatis 执行链完成外表插入，以便共享同一事务并暴露给
 * StatementHandler / Executor 拦截器；无法复用时再回退到 JDBC。</p>
 */
public interface SeparateTableRowPersister {

    /**
     * 执行独立加密表插入。
     *
     * @param request 插入请求
     * @param sourceStatement 当前业务 SQL 对应的 mapped statement
     * @param executor 当前业务 SQL 所使用的 executor
     */
    void insert(SeparateTableInsertRequest request, MappedStatement sourceStatement, Executor executor);
}
