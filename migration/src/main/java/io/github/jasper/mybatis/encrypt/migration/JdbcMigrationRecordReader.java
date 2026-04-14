package io.github.jasper.mybatis.encrypt.migration;

import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.config.SqlDialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JDBC reader that pages source rows by ordered stable cursor columns.
 */
public class JdbcMigrationRecordReader implements MigrationRecordReader, MigrationRangeReader {

    private final DatabaseEncryptionProperties properties;

    /**
     * jdbc迁移记录读取器
     * @param properties 配置
     */
    public JdbcMigrationRecordReader(DatabaseEncryptionProperties properties) {
        this.properties = properties;
    }

    @Override
    public MigrationRange readRange(Connection connection, EntityMigrationPlan plan) throws SQLException {
        long totalRows = countRows(connection, plan);
        if (totalRows == 0L) {
            return new MigrationRange(0L, null, null, java.util.Collections.<String>emptyList());
        }
        MigrationCursor rangeStart = loadBoundaryCursor(connection, plan, true);
        MigrationCursor rangeEnd = loadBoundaryCursor(connection, plan, false);
        List<String> cursorJavaTypes = resolveCursorJavaTypes(rangeStart, rangeEnd);
        return new MigrationRange(totalRows, rangeStart, rangeEnd, cursorJavaTypes);
    }

    @Override
    public List<MigrationRecord> readBatch(Connection connection,
                                           EntityMigrationPlan plan,
                                           MigrationCursor lastProcessedCursor)
            throws SQLException {
        Set<String> selectColumns = new LinkedHashSet<String>();
        selectColumns.addAll(plan.getCursorColumns());
        for (EntityMigrationColumnPlan columnPlan : plan.getColumnPlans()) {
            selectColumns.add(columnPlan.getSourceColumn());
        }
        String sql = buildSelectSql(plan, selectColumns, lastProcessedCursor != null);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameterIndex = bindCheckpoint(statement, lastProcessedCursor);
            statement.setInt(parameterIndex, plan.getBatchSize());
            try (ResultSet resultSet = statement.executeQuery()) {
                List<MigrationRecord> records = new ArrayList<MigrationRecord>();
                while (resultSet.next()) {
                    Map<String, Object> cursorValues = new LinkedHashMap<String, Object>();
                    for (String cursorColumn : plan.getCursorColumns()) {
                        Object cursorValue = resultSet.getObject(cursorColumn);
                        if (cursorValue == null) {
                            throw new MigrationException("Cursor column must not be null during migration: " + cursorColumn);
                        }
                        cursorValues.put(cursorColumn, cursorValue);
                    }
                    Map<String, Object> values = new LinkedHashMap<String, Object>();
                    for (EntityMigrationColumnPlan columnPlan : plan.getColumnPlans()) {
                        values.put(columnPlan.getSourceColumn(), resultSet.getObject(columnPlan.getSourceColumn()));
                    }
                    records.add(new MigrationRecord(new MigrationCursor(cursorValues), values));
                }
                return records;
            }
        }
    }

    /**
     * Compatibility overload for callers that still pass one single cursor scalar.
     *
     * @param connection open JDBC connection
     * @param plan migration plan
     * @param lastProcessedCursor inclusive checkpoint boundary on the cursor
     * @return next batch
     * @throws SQLException when JDBC read fails
     * @deprecated use {@link #readBatch(Connection, EntityMigrationPlan, MigrationCursor)}
     */
    @Deprecated
    public List<MigrationRecord> readBatch(Connection connection, EntityMigrationPlan plan, Object lastProcessedCursor)
            throws SQLException {
        return readBatch(connection, plan, toCursor(plan, lastProcessedCursor));
    }

    private long countRows(Connection connection, EntityMigrationPlan plan) throws SQLException {
        String sql = "select count(1) from " + quote(plan.getTableName());
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private MigrationCursor loadBoundaryCursor(Connection connection, EntityMigrationPlan plan, boolean ascending)
            throws SQLException {
        StringBuilder sql = new StringBuilder("select ");
        appendColumnList(sql, plan.getCursorColumns());
        sql.append(" from ").append(quote(plan.getTableName())).append(" order by ");
        appendOrderBy(sql, plan.getCursorColumns(), ascending ? "asc" : "desc");
        appendBatchClause(sql);
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            statement.setInt(1, 1);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                Map<String, Object> values = new LinkedHashMap<String, Object>();
                for (String cursorColumn : plan.getCursorColumns()) {
                    values.put(cursorColumn, resultSet.getObject(cursorColumn));
                }
                return new MigrationCursor(values);
            }
        }
    }

    private List<String> resolveCursorJavaTypes(MigrationCursor rangeStart, MigrationCursor rangeEnd) {
        MigrationCursor typeSource = rangeEnd != null ? rangeEnd : rangeStart;
        List<String> types = new ArrayList<String>();
        if (typeSource == null) {
            return types;
        }
        for (Object value : typeSource.getValues().values()) {
            types.add(value == null ? null : value.getClass().getName());
        }
        return types;
    }

    private MigrationCursor toCursor(EntityMigrationPlan plan, Object rawCursor) {
        if (rawCursor == null) {
            return null;
        }
        if (rawCursor instanceof MigrationCursor) {
            return (MigrationCursor) rawCursor;
        }
        if (plan.getCursorColumns().size() == 1) {
            Map<String, Object> values = new LinkedHashMap<String, Object>();
            values.put(plan.getCursorColumn(), rawCursor);
            return new MigrationCursor(values);
        }
        throw new MigrationException("Composite cursor checkpoint must use MigrationCursor: " + plan.getCursorColumns());
    }

    private String buildSelectSql(EntityMigrationPlan plan, Set<String> selectColumns, boolean withCheckpoint) {
        StringBuilder sql = new StringBuilder("select ");
        appendColumnList(sql, selectColumns);
        sql.append(" from ").append(quote(plan.getTableName()));
        if (withCheckpoint) {
            sql.append(" where ").append(buildSeekPredicate(plan.getCursorColumns()));
        }
        sql.append(" order by ");
        appendOrderBy(sql, plan.getCursorColumns(), "asc");
        appendBatchClause(sql);
        return sql.toString();
    }

    private String buildSeekPredicate(List<String> cursorColumns) {
        StringBuilder predicate = new StringBuilder();
        for (int index = 0; index < cursorColumns.size(); index++) {
            if (index > 0) {
                predicate.append(" or ");
            }
            predicate.append("(");
            for (int equalIndex = 0; equalIndex < index; equalIndex++) {
                if (equalIndex > 0) {
                    predicate.append(" and ");
                }
                predicate.append(quote(cursorColumns.get(equalIndex))).append(" = ?");
            }
            if (index > 0) {
                predicate.append(" and ");
            }
            predicate.append(quote(cursorColumns.get(index))).append(" > ?");
            predicate.append(")");
        }
        return predicate.toString();
    }

    private int bindCheckpoint(PreparedStatement statement, MigrationCursor checkpoint) throws SQLException {
        int parameterIndex = 1;
        if (checkpoint == null) {
            return parameterIndex;
        }
        List<Object> values = new ArrayList<Object>(checkpoint.getValues().values());
        for (int index = 0; index < values.size(); index++) {
            for (int equalIndex = 0; equalIndex < index; equalIndex++) {
                statement.setObject(parameterIndex++, values.get(equalIndex));
            }
            statement.setObject(parameterIndex++, values.get(index));
        }
        return parameterIndex;
    }

    private void appendColumnList(StringBuilder sql, java.util.Collection<String> columns) {
        int index = 0;
        for (String column : columns) {
            if (index++ > 0) {
                sql.append(", ");
            }
            sql.append(quote(column));
        }
    }

    private void appendOrderBy(StringBuilder sql, List<String> columns, String direction) {
        for (int index = 0; index < columns.size(); index++) {
            if (index > 0) {
                sql.append(", ");
            }
            sql.append(quote(columns.get(index))).append(" ").append(direction);
        }
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
