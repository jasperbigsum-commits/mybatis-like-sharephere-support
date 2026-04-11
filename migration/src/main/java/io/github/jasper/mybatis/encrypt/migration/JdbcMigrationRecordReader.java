package io.github.jasper.mybatis.encrypt.migration;

import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.config.SqlDialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * JDBC reader that pages source rows by the entity id column.
 */
public class JdbcMigrationRecordReader implements MigrationRecordReader, MigrationRangeReader {

    private final DatabaseEncryptionProperties properties;

    public JdbcMigrationRecordReader(DatabaseEncryptionProperties properties) {
        this.properties = properties;
    }

    @Override
    public MigrationRange readRange(Connection connection, EntityMigrationPlan plan) throws SQLException {
        String sql = "select count(1), min(" + quote(plan.getIdColumn()) + "), max(" + quote(plan.getIdColumn())
                + ") from " + quote(plan.getTableName());
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                return new MigrationRange(0L, null, null, null);
            }
            Object minValue = resultSet.getObject(2);
            Object maxValue = resultSet.getObject(3);
            String idJavaType = maxValue != null ? maxValue.getClass().getName()
                    : (minValue != null ? minValue.getClass().getName() : null);
            return new MigrationRange(resultSet.getLong(1), minValue, maxValue, idJavaType);
        }
    }

    @Override
    public List<MigrationRecord> readBatch(Connection connection, EntityMigrationPlan plan, Object lastProcessedId)
            throws SQLException {
        Set<String> selectColumns = new LinkedHashSet<String>();
        selectColumns.add(plan.getIdColumn());
        for (EntityMigrationColumnPlan columnPlan : plan.getColumnPlans()) {
            selectColumns.add(columnPlan.getSourceColumn());
        }
        String sql = buildSelectSql(plan, selectColumns, lastProcessedId != null);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameterIndex = 1;
            if (lastProcessedId != null) {
                statement.setObject(parameterIndex++, lastProcessedId);
            }
            statement.setInt(parameterIndex, plan.getBatchSize());
            try (ResultSet resultSet = statement.executeQuery()) {
                List<MigrationRecord> records = new ArrayList<MigrationRecord>();
                while (resultSet.next()) {
                    java.util.Map<String, Object> values = new java.util.LinkedHashMap<String, Object>();
                    for (EntityMigrationColumnPlan columnPlan : plan.getColumnPlans()) {
                        values.put(columnPlan.getSourceColumn(), resultSet.getObject(columnPlan.getSourceColumn()));
                    }
                    records.add(new MigrationRecord(resultSet.getObject(plan.getIdColumn()), values));
                }
                return records;
            }
        }
    }

    private String buildSelectSql(EntityMigrationPlan plan, Set<String> selectColumns, boolean withCheckpoint) {
        StringBuilder sql = new StringBuilder("select ");
        int index = 0;
        for (String column : selectColumns) {
            if (index++ > 0) {
                sql.append(", ");
            }
            sql.append(quote(column));
        }
        sql.append(" from ").append(quote(plan.getTableName()));
        if (withCheckpoint) {
            sql.append(" where ").append(quote(plan.getIdColumn())).append(" > ?");
        }
        sql.append(" order by ").append(quote(plan.getIdColumn())).append(" asc");
        appendBatchClause(sql);
        return sql.toString();
    }

    private void appendBatchClause(StringBuilder sql) {
        SqlDialect dialect = properties.getSqlDialect();
        if (dialect == SqlDialect.ORACLE12 || dialect == SqlDialect.DM) {
            sql.append(" fetch first ? rows only");
            return;
        }
        sql.append(" limit ?");
    }

    private String quote(String identifier) {
        return properties.getSqlDialect().quote(identifier);
    }
}
