package io.github.jasper.mybatis.encrypt.jdbc;

import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.config.SqlDialectContextHolder;
import io.github.jasper.mybatis.encrypt.core.decrypt.QueryResultPlan;
import io.github.jasper.mybatis.encrypt.core.decrypt.ResultDecryptor;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import io.github.jasper.mybatis.encrypt.core.rewrite.RewriteResult;
import io.github.jasper.mybatis.encrypt.core.rewrite.SqlRewriteEngine;
import io.github.jasper.mybatis.encrypt.core.support.DataSourceNameResolver;
import io.github.jasper.mybatis.encrypt.util.StringUtils;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.util.LinkedCaseInsensitiveMap;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Default JDBC facade for encrypted SQL execution.
 */
public class DefaultEncryptedJdbcExecutor implements EncryptedJdbcExecutor {

    private static final String STATEMENT_ID_PREFIX = DefaultEncryptedJdbcExecutor.class.getName() + ".";

    private final Map<String, DataSource> dataSources;
    private final DataSourceNameResolver dataSourceNameResolver;
    private final SqlRewriteEngine sqlRewriteEngine;
    private final ResultDecryptor resultDecryptor;
    private final EncryptMetadataRegistry metadataRegistry;
    private final DatabaseEncryptionProperties properties;

    public DefaultEncryptedJdbcExecutor(Map<String, DataSource> dataSources,
                                        DataSourceNameResolver dataSourceNameResolver,
                                        SqlRewriteEngine sqlRewriteEngine,
                                        ResultDecryptor resultDecryptor,
                                        EncryptMetadataRegistry metadataRegistry,
                                        DatabaseEncryptionProperties properties) {
        this.dataSources = dataSources == null ? Collections.emptyMap() : new LinkedHashMap<>(dataSources);
        this.dataSourceNameResolver = dataSourceNameResolver;
        this.sqlRewriteEngine = sqlRewriteEngine;
        this.resultDecryptor = resultDecryptor;
        this.metadataRegistry = metadataRegistry;
        this.properties = properties == null ? new DatabaseEncryptionProperties() : properties;
    }

    @Override
    public List<Map<String, Object>> select(String dataSourceName, String sql, Object... args) {
        return executeQuery(dataSourceName, sql, args);
    }

    @Override
    public int insert(String dataSourceName, String sql, Object... args) {
        return executeUpdate(dataSourceName, SqlCommandType.INSERT, sql, args);
    }

    @Override
    public int update(String dataSourceName, String sql, Object... args) {
        return executeUpdate(dataSourceName, SqlCommandType.UPDATE, sql, args);
    }

    @Override
    public int delete(String dataSourceName, String sql, Object... args) {
        return executeUpdate(dataSourceName, SqlCommandType.DELETE, sql, args);
    }

    private List<Map<String, Object>> executeQuery(String dataSourceName, String sql, Object[] args) {
        ExecutionContext context = rewrite(dataSourceName, SqlCommandType.SELECT, sql, args);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(resolveDataSource(context.dataSourceName));
        List<Map<String, Object>> rows = jdbcTemplate.query(context.sql, context.arguments.toArray(), new MapRowMapper());
        if (rows.isEmpty() || resultDecryptor == null) {
            return rows;
        }
        MappedStatement mappedStatement = mappedStatement(
                context.dataSourceName, SqlCommandType.SELECT, context.sql, new Object[0]);
        QueryResultPlan queryResultPlan = resultDecryptor.resolvePlan(mappedStatement, context.boundSql);
        if (queryResultPlan.isEmpty()) {
            return rows;
        }
        resultDecryptor.decrypt(rows, queryResultPlan);
        return rows;
    }

    private int executeUpdate(String dataSourceName, SqlCommandType commandType, String sql, Object[] args) {
        ExecutionContext context = rewrite(dataSourceName, commandType, sql, args);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(resolveDataSource(context.dataSourceName));
        return jdbcTemplate.update(context.sql, context.arguments.toArray());
    }

    private ExecutionContext rewrite(String dataSourceName, SqlCommandType commandType, String sql, Object[] args) {
        String resolvedDataSourceName = normalizeDataSourceName(dataSourceName);
        try (SqlDialectContextHolder.Scope ignored = SqlDialectContextHolder.open(resolvedDataSourceName)) {
            Object parameterObject = parameterObject(args);
            MappedStatement mappedStatement = mappedStatement(resolvedDataSourceName, commandType, sql, args);
            BoundSql boundSql = mappedStatement.getBoundSql(parameterObject);
            RewriteResult rewriteResult = sqlRewriteEngine.rewrite(mappedStatement, boundSql);
            if (rewriteResult.changed()) {
                rewriteResult.applyTo(boundSql);
                sql = boundSql.getSql();
            }
            return new ExecutionContext(resolvedDataSourceName, sql, extractArguments(boundSql, parameterObject), boundSql);
        }
    }

    private DataSource resolveDataSource(String dataSourceName) {
        if (StringUtils.isNotBlank(dataSourceName) && dataSources.containsKey(dataSourceName)) {
            return dataSources.get(dataSourceName);
        }
        if (dataSourceNameResolver != null) {
            String defaultName = dataSourceNameResolver.getDefaultDataSourceName();
            if (StringUtils.isNotBlank(defaultName) && dataSources.containsKey(defaultName)) {
                return dataSources.get(defaultName);
            }
        }
        if (!dataSources.isEmpty()) {
            return dataSources.values().iterator().next();
        }
        throw new IllegalStateException("No datasource available for encrypted JDBC execution.");
    }

    private String normalizeDataSourceName(String dataSourceName) {
        if (StringUtils.isNotBlank(dataSourceName)) {
            return dataSourceName;
        }
        return dataSourceNameResolver == null ? null : dataSourceNameResolver.getDefaultDataSourceName();
    }

    private MappedStatement mappedStatement(String dataSourceName, SqlCommandType commandType, String sql, Object[] args) {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        List<ParameterMapping> parameterMappings = parameterMappings(configuration, args);
        SqlSource sqlSource = parameterObject -> new BoundSql(configuration, sql, parameterMappings, parameterObject);
        return new MappedStatement.Builder(configuration, STATEMENT_ID_PREFIX + commandType.name().toLowerCase() + "." + Integer.toHexString((sql == null ? 0 : sql.hashCode()) ^ (dataSourceName == null ? 0 : dataSourceName.hashCode())), sqlSource, commandType)
                .resultMaps(Collections.singletonList(new ResultMap.Builder(configuration, STATEMENT_ID_PREFIX + "map", Map.class, Collections.<ResultMapping>emptyList()).build()))
                .build();
    }

    private List<ParameterMapping> parameterMappings(Configuration configuration, Object[] args) {
        if (args == null || args.length == 0) {
            return Collections.emptyList();
        }
        List<ParameterMapping> mappings = new ArrayList<ParameterMapping>(args.length);
        for (int index = 0; index < args.length; index++) {
            Object value = args[index];
            Class<?> javaType = value == null ? Object.class : value.getClass();
            mappings.add(new ParameterMapping.Builder(configuration, "param" + (index + 1), javaType).build());
        }
        return mappings;
    }

    private Object parameterObject(Object[] args) {
        Map<String, Object> parameterMap = new LinkedHashMap<String, Object>();
        if (args == null || args.length == 0) {
            return parameterMap;
        }
        for (int index = 0; index < args.length; index++) {
            parameterMap.put("param" + (index + 1), args[index]);
        }
        return parameterMap;
    }

    private List<Object> extractArguments(BoundSql boundSql, Object parameterObject) {
        if (boundSql == null || boundSql.getParameterMappings() == null || boundSql.getParameterMappings().isEmpty()) {
            return Collections.emptyList();
        }
        List<Object> arguments = new ArrayList<Object>(boundSql.getParameterMappings().size());
        for (ParameterMapping mapping : boundSql.getParameterMappings()) {
            String property = mapping.getProperty();
            if (boundSql.hasAdditionalParameter(property)) {
                arguments.add(boundSql.getAdditionalParameter(property));
                continue;
            }
            arguments.add(resolvePropertyValue(parameterObject, property));
        }
        return arguments;
    }

    private Object resolvePropertyValue(Object parameterObject, String property) {
        if (parameterObject == null || StringUtils.isBlank(property)) {
            return null;
        }
        if (parameterObject instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) parameterObject;
            if (map.containsKey(property)) {
                return map.get(property);
            }
            String root = property;
            int dotIndex = property.lastIndexOf('.');
            if (dotIndex >= 0) {
                root = property.substring(0, dotIndex);
            }
            return map.get(root);
        }
        MetaObject metaObject = SystemMetaObject.forObject(parameterObject);
        return metaObject.hasGetter(property) ? metaObject.getValue(property) : null;
    }

    private static final class ExecutionContext {
        private final String dataSourceName;
        private final String sql;
        private final List<Object> arguments;
        private final BoundSql boundSql;

        private ExecutionContext(String dataSourceName, String sql, List<Object> arguments) {
            this(dataSourceName, sql, arguments, null);
        }

        private ExecutionContext(String dataSourceName, String sql, List<Object> arguments, BoundSql boundSql) {
            this.dataSourceName = dataSourceName;
            this.sql = sql;
            this.arguments = arguments;
            this.boundSql = boundSql;
        }
    }

    private static final class MapRowMapper implements RowMapper<Map<String, Object>> {
        @Override
        public Map<String, Object> mapRow(ResultSet rs, int rowNum) throws SQLException {
            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();
            Map<String, Object> row = new LinkedCaseInsensitiveMap<Object>(columnCount);
            for (int index = 1; index <= columnCount; index++) {
                row.put(metaData.getColumnLabel(index), rs.getObject(index));
            }
            return row;
        }
    }
}
