package io.github.jasper.mybatis.encrypt.migration;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.util.StringUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Default JDBC writer for both same-table and separate-table migration modes.
 */
public class JdbcMigrationRecordWriter implements MigrationRecordWriter {

    private final DatabaseEncryptionProperties properties;
    private final ReferenceIdGenerator referenceIdGenerator;
    private final MigrationValueResolver valueResolver;

    /**
     * jdbc迁移记录写入
     * @param properties 配置文件
     * @param algorithmRegistry 加密算法
     * @param referenceIdGenerator 引用id生成器
     */
    public JdbcMigrationRecordWriter(DatabaseEncryptionProperties properties,
                                     AlgorithmRegistry algorithmRegistry,
                                     ReferenceIdGenerator referenceIdGenerator) {
        this.properties = properties;
        this.referenceIdGenerator = referenceIdGenerator;
        this.valueResolver = new MigrationValueResolver(algorithmRegistry);
    }

    @Override
    public boolean write(Connection connection, EntityMigrationPlan plan, MigrationRecord record) throws SQLException {
        Map<String, Object> mainTableUpdates = new LinkedHashMap<String, Object>();
        for (EntityMigrationColumnPlan columnPlan : plan.getColumnPlans()) {
            Object plainValue = record.getColumnValue(columnPlan.getSourceColumn());
            MigrationValueResolver.DerivedFieldValues derivedFieldValues = valueResolver.resolve(columnPlan, plainValue);
            if (derivedFieldValues.isEmpty()) {
                continue;
            }
            if (columnPlan.shouldWriteBackup()) {
                mainTableUpdates.put(columnPlan.getBackupColumn(), plainValue);
            }
            if (columnPlan.isStoredInSeparateTable()) {
                String referenceHash = derivedFieldValues.getHashValue();
                if (!existsSeparateReference(connection, columnPlan, referenceHash)) {
                    Object storageId = referenceIdGenerator.nextReferenceId(columnPlan, record);
                    insertSeparateRow(connection, columnPlan, storageId, derivedFieldValues);
                }
                mainTableUpdates.put(columnPlan.getSourceColumn(), referenceHash);
                continue;
            }
            mainTableUpdates.put(columnPlan.getStorageColumn(), derivedFieldValues.getCipherText());
            if (StringUtils.isNotBlank(columnPlan.getAssistedQueryColumn())) {
                mainTableUpdates.put(columnPlan.getAssistedQueryColumn(), derivedFieldValues.getHashValue());
            }
            if (StringUtils.isNotBlank(columnPlan.getLikeQueryColumn())) {
                mainTableUpdates.put(columnPlan.getLikeQueryColumn(), derivedFieldValues.getLikeValue());
            }
        }
        if (mainTableUpdates.isEmpty()) {
            return false;
        }
        updateMainTable(connection, plan, record.getCursor(), mainTableUpdates);
        return true;
    }

    private void updateMainTable(Connection connection,
                                 EntityMigrationPlan plan,
                                 MigrationCursor rowCursor,
                                 Map<String, Object> updates)
            throws SQLException {
        StringBuilder sql = new StringBuilder("update ").append(quote(plan.getTableName())).append(" set ");
        int index = 0;
        for (String column : updates.keySet()) {
            if (index++ > 0) {
                sql.append(", ");
            }
            sql.append(quote(column)).append(" = ?");
        }
        sql.append(" where ");
        appendCursorEqualityPredicate(sql, plan.getCursorColumns());
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int parameterIndex = 1;
            for (Object value : updates.values()) {
                statement.setObject(parameterIndex++, value);
            }
            for (Object cursorValue : rowCursor.getValues().values()) {
                statement.setObject(parameterIndex++, cursorValue);
            }
            statement.executeUpdate();
        }
    }

    private void appendCursorEqualityPredicate(StringBuilder sql, List<String> cursorColumns) {
        for (int index = 0; index < cursorColumns.size(); index++) {
            if (index > 0) {
                sql.append(" and ");
            }
            sql.append(quote(cursorColumns.get(index))).append(" = ?");
        }
    }

    private boolean existsSeparateReference(Connection connection, EntityMigrationColumnPlan columnPlan, String referenceHash)
            throws SQLException {
        if (referenceHash == null) {
            return false;
        }
        String sql = "select 1 from " + quote(columnPlan.getStorageTable())
                + " where " + quote(columnPlan.getAssistedQueryColumn()) + " = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, referenceHash);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private void insertSeparateRow(Connection connection,
                                   EntityMigrationColumnPlan columnPlan,
                                   Object referenceId,
                                   MigrationValueResolver.DerivedFieldValues values) throws SQLException {
        List<String> columns = new ArrayList<String>();
        List<Object> bindValues = new ArrayList<Object>();
        columns.add(columnPlan.getStorageIdColumn());
        bindValues.add(referenceId);
        columns.add(columnPlan.getStorageColumn());
        bindValues.add(values.getCipherText());
        if (StringUtils.isNotBlank(columnPlan.getAssistedQueryColumn())) {
            columns.add(columnPlan.getAssistedQueryColumn());
            bindValues.add(values.getHashValue());
        }
        if (StringUtils.isNotBlank(columnPlan.getLikeQueryColumn())) {
            columns.add(columnPlan.getLikeQueryColumn());
            bindValues.add(values.getLikeValue());
        }
        StringBuilder sql = new StringBuilder("insert into ").append(quote(columnPlan.getStorageTable())).append(" (");
        for (int index = 0; index < columns.size(); index++) {
            if (index > 0) {
                sql.append(", ");
            }
            sql.append(quote(columns.get(index)));
        }
        sql.append(") values (");
        for (int index = 0; index < bindValues.size(); index++) {
            if (index > 0) {
                sql.append(", ");
            }
            sql.append("?");
        }
        sql.append(")");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int index = 0; index < bindValues.size(); index++) {
                statement.setObject(index + 1, bindValues.get(index));
            }
            statement.executeUpdate();
        }
    }

    private String quote(String identifier) {
        return properties.getSqlDialect().quote(identifier);
    }
}
