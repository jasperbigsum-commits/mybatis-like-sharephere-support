package io.github.jasper.mybatis.encrypt.core.support;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.core.decrypt.QueryResultPlan;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptJsonPathRule;
import io.github.jasper.mybatis.encrypt.core.rewrite.EncryptJsonCipherLookup;
import io.github.jasper.mybatis.encrypt.core.rewrite.EncryptJsonWriteResult;
import io.github.jasper.mybatis.encrypt.core.rewrite.ParameterValueResolver;
import io.github.jasper.mybatis.encrypt.exception.EncryptionConfigurationException;
import io.github.jasper.mybatis.encrypt.exception.EncryptionErrorCode;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 独立加密表管理器。
 *
 * <p>作为独立表能力的统一门面，对外只暴露写前引用准备和查询后回填两个入口。
 * 具体实现拆分给写前引用准备器与结果回填器，避免单类同时承担过多运行时职责。</p>
 */
public class SeparateTableEncryptionManager {

    private final SeparateTableReferencePreparer referencePreparer;
    private final SeparateTableResultHydrator resultHydrator;
    private final EncryptJsonCipherLookup jsonCipherLookup;
    private final DataSource dataSource;
    private final DatabaseEncryptionProperties properties;
    private final SeparateTableRowPersister rowPersister;
    private final SnowflakeIdGenerator snowflakeIdGenerator = new SnowflakeIdGenerator();

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
        this.dataSource = dataSource;
        this.properties = properties == null ? new DatabaseEncryptionProperties() : properties;
        this.rowPersister = rowPersister;
        SeparateTableRuleSupport ruleSupport = new SeparateTableRuleSupport(algorithmRegistry, properties);
        this.referencePreparer = new SeparateTableReferencePreparer(
                dataSource, metadataRegistry, algorithmRegistry, rowPersister, ruleSupport);
        this.resultHydrator = new SeparateTableResultHydrator(
                dataSource, metadataRegistry, algorithmRegistry, properties, ruleSupport);
        this.jsonCipherLookup = new JdbcEncryptJsonCipherLookup(dataSource, this.properties);
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
     * 把当前语句准备好的 JSON path 独立表写入落库。
     *
     * @param mappedStatement 当前业务 statement
     * @param boundSql 当前 BoundSql
     * @param executor 当前 executor
     */
    public void persistPreparedJsonPathWrites(MappedStatement mappedStatement, BoundSql boundSql, Executor executor) {
        if (boundSql == null
                || !boundSql.hasAdditionalParameter(ParameterValueResolver.PREPARED_JSON_PATH_WRITES_PARAMETER)) {
            return;
        }
        @SuppressWarnings("unchecked")
        List<EncryptJsonWriteResult.PathWrite> writes =
                (List<EncryptJsonWriteResult.PathWrite>) boundSql.getAdditionalParameter(
                        ParameterValueResolver.PREPARED_JSON_PATH_WRITES_PARAMETER);
        if (writes == null || writes.isEmpty()) {
            return;
        }
        Map<String, EncryptJsonWriteResult.PathWrite> uniqueWrites =
                new LinkedHashMap<String, EncryptJsonWriteResult.PathWrite>();
        for (EncryptJsonWriteResult.PathWrite write : writes) {
            String key = write.pathRule().storageTable() + "|" + write.pathRule().hashColumn() + "|" + write.hashValue();
            uniqueWrites.putIfAbsent(key, write);
        }
        for (EncryptJsonWriteResult.PathWrite write : new ArrayList<EncryptJsonWriteResult.PathWrite>(uniqueWrites.values())) {
            if (!existsJsonPathHash(write.pathRule(), write.hashValue())) {
                insertJsonPathRow(write, mappedStatement, executor);
            }
        }
    }

    /**
     * 返回 JSON path 密文查找器。
     *
     * @return JSON path 密文查找器
     */
    public EncryptJsonCipherLookup jsonCipherLookup() {
        return jsonCipherLookup;
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

    private boolean existsJsonPathHash(EncryptJsonPathRule pathRule, String hashValue) {
        if (dataSource == null || pathRule == null || hashValue == null) {
            return false;
        }
        String sql = "select 1 from " + quote(pathRule.storageTable())
                + " where " + quote(pathRule.hashColumn()) + " = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, hashValue);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException ex) {
            throw new EncryptionConfigurationException(EncryptionErrorCode.SEPARATE_TABLE_OPERATION_FAILED,
                    "Failed to check EncryptJsonField external row.", ex);
        }
    }

    private void insertJsonPathRow(EncryptJsonWriteResult.PathWrite write,
                                   MappedStatement mappedStatement,
                                   Executor executor) {
        if (rowPersister == null) {
            throw new EncryptionConfigurationException(EncryptionErrorCode.SEPARATE_TABLE_OPERATION_FAILED,
                    "Missing separate-table row persister for EncryptJsonField writes.");
        }
        LinkedHashMap<String, Object> columnValues = new LinkedHashMap<String, Object>();
        columnValues.put(write.pathRule().storageIdColumn(), snowflakeIdGenerator.nextId());
        columnValues.put(write.pathRule().hashColumn(), write.hashValue());
        columnValues.put(write.pathRule().cipherColumn(), write.cipherValue());
        rowPersister.insert(new SeparateTableInsertRequest(write.pathRule().storageTable(), columnValues),
                mappedStatement, executor);
    }

    private String quote(String identifier) {
        return properties.getSqlDialect().quote(identifier);
    }

    private static final class JdbcEncryptJsonCipherLookup implements EncryptJsonCipherLookup {

        private final DataSource dataSource;
        private final DatabaseEncryptionProperties properties;

        private JdbcEncryptJsonCipherLookup(DataSource dataSource, DatabaseEncryptionProperties properties) {
            this.dataSource = dataSource;
            this.properties = properties == null ? new DatabaseEncryptionProperties() : properties;
        }

        @Override
        public String findCipher(EncryptJsonPathRule pathRule, String currentHashValue) {
            if (pathRule == null || currentHashValue == null) {
                return null;
            }
            String sql = "select " + quote(pathRule.cipherColumn())
                    + " from " + quote(pathRule.storageTable())
                    + " where " + quote(pathRule.hashColumn()) + " = ?";
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, currentHashValue);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? resultSet.getString(1) : null;
                }
            } catch (SQLException ex) {
                throw new EncryptionConfigurationException(EncryptionErrorCode.SEPARATE_TABLE_OPERATION_FAILED,
                        "Failed to load EncryptJsonField cipher value.", ex);
            }
        }

        private String quote(String identifier) {
            return properties.getSqlDialect().quote(identifier);
        }
    }
}
