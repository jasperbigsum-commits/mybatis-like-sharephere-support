package io.github.jasper.mybatis.encrypt.core.support;

import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.exception.EncryptionConfigurationException;
import io.github.jasper.mybatis.encrypt.exception.EncryptionErrorCode;
import org.apache.ibatis.executor.BatchExecutor;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.keygen.NoKeyGenerator;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.scripting.defaults.RawSqlSource;
import org.apache.ibatis.session.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 默认独立加密表写入执行器。
 *
 * <p>若当前处于 MyBatis Executor 调用链中，则动态构造一个以 Map 为参数的 INSERT
 * mapped statement 并通过当前 executor 执行；这样外表写入会落在同一事务里，且能被
 * 其他 MyBatis 拦截器观察和扩展。没有可用 executor 时才回退到 JDBC。</p>
 */
public class DefaultSeparateTableRowPersister implements SeparateTableRowPersister {

    private static final String STATEMENT_ID_PREFIX =
            DefaultSeparateTableRowPersister.class.getName() + ".insert.";

    private final DataSource dataSource;
    private final DatabaseEncryptionProperties properties;
    private final ConcurrentMap<String, String> statementIds = new ConcurrentHashMap<>();

    /**
     * 创建默认独立表写入执行器。
     *
     * @param dataSource 数据源
     * @param properties 插件配置
     */
    public DefaultSeparateTableRowPersister(DataSource dataSource, DatabaseEncryptionProperties properties) {
        this.dataSource = dataSource;
        this.properties = properties;
    }

    /**
     * 判断当前 mapped statement 是否为框架内部生成的独立表写入语句。
     *
     * @param statementId mapped statement id
     * @return 属于内部独立表语句时返回 {@code true}
     */
    public static boolean isManagedStatementId(String statementId) {
        return statementId != null && statementId.startsWith(STATEMENT_ID_PREFIX);
    }

    @Override
    public void insert(SeparateTableInsertRequest request, MappedStatement sourceStatement, Executor executor) {
        if (request == null || request.getColumnValues().isEmpty()) {
            throw new EncryptionConfigurationException(EncryptionErrorCode.SEPARATE_TABLE_OPERATION_FAILED,
                    "Separate-table insert request must not be empty.");
        }
        if (sourceStatement != null && executor != null) {
            insertWithMyBatis(request, sourceStatement, executor);
            return;
        }
        insertWithJdbc(request);
    }

    private void insertWithMyBatis(SeparateTableInsertRequest request,
                                   MappedStatement sourceStatement,
                                   Executor executor) {
        MappedStatement mappedStatement = resolveMappedStatement(sourceStatement.getConfiguration(), request);
        Map<String, Object> parameterObject = new LinkedHashMap<>(request.getColumnValues());
        try {
            int updated = executor.update(mappedStatement, parameterObject);
            if (updated != 1 && updated != BatchExecutor.BATCH_UPDATE_RETURN_VALUE) {
                throw new EncryptionConfigurationException(EncryptionErrorCode.SEPARATE_TABLE_OPERATION_FAILED,
                        "Unexpected separate-table insert row count: " + updated);
            }
        } catch (EncryptionConfigurationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new EncryptionConfigurationException(EncryptionErrorCode.SEPARATE_TABLE_OPERATION_FAILED,
                    "Failed to insert separate-table encrypted value.", ex);
        }
    }

    private void insertWithJdbc(SeparateTableInsertRequest request) {
        String sql = buildInsertSql(request);
        List<Object> values = new ArrayList<>(request.getColumnValues().values());
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            int updated = statement.executeUpdate();
            if (updated != 1) {
                throw new EncryptionConfigurationException(EncryptionErrorCode.SEPARATE_TABLE_OPERATION_FAILED,
                        "Unexpected separate-table insert row count: " + updated);
            }
        } catch (SQLException ex) {
            throw new EncryptionConfigurationException(EncryptionErrorCode.SEPARATE_TABLE_OPERATION_FAILED,
                    "Failed to insert separate-table encrypted value.", ex);
        }
    }

    private MappedStatement resolveMappedStatement(Configuration configuration, SeparateTableInsertRequest request) {
        String statementId = statementIds.computeIfAbsent(statementKey(request), ignored -> buildStatementId(request));
        if (configuration.hasStatement(statementId, false)) {
            return configuration.getMappedStatement(statementId, false);
        }
        synchronized (configuration) {
            if (configuration.hasStatement(statementId, false)) {
                return configuration.getMappedStatement(statementId, false);
            }
            configuration.addMappedStatement(buildMappedStatement(configuration, statementId, request));
            return configuration.getMappedStatement(statementId, false);
        }
    }

    private MappedStatement buildMappedStatement(Configuration configuration,
                                                 String statementId,
                                                 SeparateTableInsertRequest request) {
        SqlSource sqlSource = new RawSqlSource(configuration, buildNamedInsertSql(request), Map.class);
        MappedStatement.Builder builder =
                new MappedStatement.Builder(configuration, statementId, sqlSource, SqlCommandType.INSERT);
        builder.statementType(StatementType.PREPARED);
        builder.keyGenerator(NoKeyGenerator.INSTANCE);
        builder.resultMaps(Collections.singletonList(new ResultMap.Builder(
                configuration, statementId + ".inline", Map.class, Collections.emptyList()).build()));
        return builder.build();
    }

    private String buildInsertSql(SeparateTableInsertRequest request) {
        String columns = request.getColumnValues().keySet().stream()
                .map(this::quote)
                .reduce((left, right) -> left + ", " + right)
                .orElseThrow(() -> new EncryptionConfigurationException(
                        EncryptionErrorCode.SEPARATE_TABLE_OPERATION_FAILED,
                        "Missing separate-table insert columns."));
        String placeholders = request.getColumnValues().values().stream()
                .map(value -> "?")
                .reduce((left, right) -> left + ", " + right)
                .orElseThrow(() -> new EncryptionConfigurationException(
                        EncryptionErrorCode.SEPARATE_TABLE_OPERATION_FAILED,
                        "Missing separate-table insert values."));
        return "insert into " + quote(request.getTable()) + " (" + columns + ") values (" + placeholders + ")";
    }

    private String buildNamedInsertSql(SeparateTableInsertRequest request) {
        String columns = request.getColumnValues().keySet().stream()
                .map(this::quote)
                .reduce((left, right) -> left + ", " + right)
                .orElseThrow(() -> new EncryptionConfigurationException(
                        EncryptionErrorCode.SEPARATE_TABLE_OPERATION_FAILED,
                        "Missing separate-table insert columns."));
        String placeholders = request.getColumnValues().keySet().stream()
                .map(column -> "#{" + column + "}")
                .reduce((left, right) -> left + ", " + right)
                .orElseThrow(() -> new EncryptionConfigurationException(
                        EncryptionErrorCode.SEPARATE_TABLE_OPERATION_FAILED,
                        "Missing separate-table insert values."));
        return "insert into " + quote(request.getTable()) + " (" + columns + ") values (" + placeholders + ")";
    }

    private String buildStatementId(SeparateTableInsertRequest request) {
        return STATEMENT_ID_PREFIX + sanitize(request.getTable()) + "." + Integer.toHexString(statementKey(request).hashCode());
    }

    private String statementKey(SeparateTableInsertRequest request) {
        return request.getTable() + "|" + String.join(",", request.getColumnValues().keySet());
    }

    private String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private String quote(String identifier) {
        return properties.getSqlDialect().quote(identifier);
    }

    private void bind(PreparedStatement statement, List<Object> values) throws SQLException {
        for (int index = 0; index < values.size(); index++) {
            statement.setObject(index + 1, values.get(index));
        }
    }
}
