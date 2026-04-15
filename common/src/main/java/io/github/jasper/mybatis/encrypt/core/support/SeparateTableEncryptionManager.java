package io.github.jasper.mybatis.encrypt.core.support;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.core.decrypt.QueryResultPlan;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;

import javax.sql.DataSource;

/**
 * 独立加密表管理器。
 *
 * <p>作为独立表能力的统一门面，对外只暴露写前引用准备和查询后回填两个入口。
 * 具体实现拆分给写前引用准备器与结果回填器，避免单类同时承担过多运行时职责。</p>
 */
public class SeparateTableEncryptionManager {

    private final SeparateTableReferencePreparer referencePreparer;
    private final SeparateTableResultHydrator resultHydrator;

    /**
     * 创建独立表加密管理器。
     *
     * @param dataSource 数据源
     * @param metadataRegistry 加密元数据注册中心
     * @param algorithmRegistry 算法注册中心
     * @param properties 插件配置属性
     */
    public SeparateTableEncryptionManager(DataSource dataSource,
                                          EncryptMetadataRegistry metadataRegistry,
                                          AlgorithmRegistry algorithmRegistry,
                                          DatabaseEncryptionProperties properties) {
        this(dataSource, metadataRegistry, algorithmRegistry, properties,
                new DefaultSeparateTableRowPersister(dataSource, properties));
    }

    /**
     * 创建独立表加密管理器。
     *
     * @param dataSource 数据源
     * @param metadataRegistry 加密元数据注册中心
     * @param algorithmRegistry 算法注册中心
     * @param properties 插件配置属性
     * @param rowPersister 独立表写入执行器
     */
    public SeparateTableEncryptionManager(DataSource dataSource,
                                          EncryptMetadataRegistry metadataRegistry,
                                          AlgorithmRegistry algorithmRegistry,
                                          DatabaseEncryptionProperties properties,
                                          SeparateTableRowPersister rowPersister) {
        SeparateTableRuleSupport ruleSupport = new SeparateTableRuleSupport(algorithmRegistry, properties);
        this.referencePreparer = new SeparateTableReferencePreparer(
                dataSource, metadataRegistry, algorithmRegistry, rowPersister, ruleSupport);
        this.resultHydrator = new SeparateTableResultHydrator(
                dataSource, metadataRegistry, algorithmRegistry, ruleSupport);
    }

    /**
     * 为写操作预先准备独立表 hash 引用值。
     *
     * @param mappedStatement 当前 mapped statement
     * @param boundSql 当前 BoundSql
     */
    public void prepareWriteReferences(MappedStatement mappedStatement, BoundSql boundSql) {
        prepareWriteReferences(mappedStatement, boundSql, null);
    }

    /**
     * 为写操作预先准备独立表 hash 引用值。
     *
     * @param mappedStatement 当前 mapped statement
     * @param boundSql 当前 BoundSql
     * @param executor 当前业务 SQL 所使用的 executor
     */
    public void prepareWriteReferences(MappedStatement mappedStatement, BoundSql boundSql, Executor executor) {
        if (usesLegacyPrepareOverride()) {
            prepareWriteReferences(mappedStatement, boundSql);
            return;
        }
        referencePreparer.prepareWriteReferences(mappedStatement, boundSql, executor);
    }

    /**
     * 对查询结果执行独立表字段回填与解密。
     *
     * @param resultObject 查询结果对象或集合
     */
    public void hydrateResults(Object resultObject) {
        resultHydrator.hydrateResults(resultObject);
    }

    /**
     * 对查询结果执行独立表字段回填与解密，并优先使用本次查询的结果计划。
     *
     * @param resultObject 查询结果对象或集合
     * @param queryResultPlan 当前查询结果计划
     */
    public void hydrateResults(Object resultObject, QueryResultPlan queryResultPlan) {
        resultHydrator.hydrateResults(resultObject, queryResultPlan);
    }

    /**
     * 打开一次查询结果回填作用域。
     */
    public void beginQueryScope() {
        resultHydrator.beginQueryScope();
    }

    /**
     * 关闭一次查询结果回填作用域。
     */
    public void endQueryScope() {
        resultHydrator.endQueryScope();
    }

    private boolean usesLegacyPrepareOverride() {
        try {
            return getClass()
                    .getMethod("prepareWriteReferences", MappedStatement.class, BoundSql.class)
                    .getDeclaringClass() != SeparateTableEncryptionManager.class;
        } catch (NoSuchMethodException ex) {
            return false;
        }
    }
}
