package io.github.jasper.mybatis.encrypt.migration.jdbc;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.migration.EntityMigrationColumnPlan;
import io.github.jasper.mybatis.encrypt.migration.EntityMigrationPlan;
import io.github.jasper.mybatis.encrypt.migration.MigrationCursor;
import io.github.jasper.mybatis.encrypt.migration.MigrationExecutionException;
import io.github.jasper.mybatis.encrypt.migration.MigrationErrorCode;
import io.github.jasper.mybatis.encrypt.migration.MigrationRecord;
import io.github.jasper.mybatis.encrypt.migration.MigrationRecordStateInspector;
import io.github.jasper.mybatis.encrypt.migration.MigrationRecordWriter;
import io.github.jasper.mybatis.encrypt.migration.ReferenceIdGenerator;
import io.github.jasper.mybatis.encrypt.util.StringUtils;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default JDBC writer for both same-table and separate-table migration modes.
 */
public class JdbcMigrationRecordWriter implements MigrationRecordWriter, MigrationRecordStateInspector {

    private static final Logger log = LoggerFactory.getLogger(JdbcMigrationRecordWriter.class);

    private final DatabaseEncryptionProperties properties;
    private final ReferenceIdGenerator referenceIdGenerator;
    private final MigrationValueResolver valueResolver;
    private final MigrationRecordStateSupport recordStateSupport;

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
        this.recordStateSupport = new MigrationRecordStateSupport(properties);
    }

    @Override
    public boolean write(Connection connection, EntityMigrationPlan plan, MigrationRecord record) throws SQLException {
        Map<String, Object> currentRow = loadCurrentMainRow(connection, plan, record.getCursor());
        Map<String, Object> mainTableUpdates = new LinkedHashMap<>();
        for (EntityMigrationColumnPlan columnPlan : plan.getColumnPlans()) {
            if (isAlreadyMigratedWithoutPlaintext(connection, columnPlan, currentRow)) {
                continue;
            }
            ensurePlaintextRecoverable(connection, plan, columnPlan, currentRow);
            Object plainValue = recordStateSupport.resolvePlainValue(columnPlan, record, currentRow);
            MigrationValueResolver.DerivedFieldValues derivedFieldValues = valueResolver.resolve(columnPlan, plainValue);
            if (derivedFieldValues.isEmpty()) {
                continue;
            }
            if (isAlreadyInTargetState(connection, columnPlan, currentRow, plainValue, derivedFieldValues)) {
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
            if (columnPlan.hasDistinctMaskedColumn()) {
                mainTableUpdates.put(columnPlan.getMaskedColumn(), derivedFieldValues.getMaskedValue());
            }
        }
        if (mainTableUpdates.isEmpty()) {
            return false;
        }
        updateMainTable(connection, plan, record.getCursor(), mainTableUpdates);
        return true;
    }

    @Override
    public boolean requiresMigration(Connection connection, EntityMigrationPlan plan, MigrationRecord record) throws SQLException {
        Map<String, Object> currentRow = loadCurrentMainRow(connection, plan, record.getCursor());
        for (EntityMigrationColumnPlan columnPlan : plan.getColumnPlans()) {
            if (isAlreadyMigratedWithoutPlaintext(connection, columnPlan, currentRow)) {
                continue;
            }
            ensurePlaintextRecoverable(connection, plan, columnPlan, currentRow);
            Object plainValue = recordStateSupport.resolvePlainValue(columnPlan, record, currentRow);
            MigrationValueResolver.DerivedFieldValues derivedFieldValues = valueResolver.resolve(columnPlan, plainValue);
            if (derivedFieldValues.isEmpty()) {
                continue;
            }
            if (!isAlreadyInTargetState(connection, columnPlan, currentRow, plainValue, derivedFieldValues)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> loadCurrentMainRow(Connection connection,
                                                   EntityMigrationPlan plan,
                                                   MigrationCursor rowCursor) throws SQLException {
        Set<String> columns = new LinkedHashSet<String>();
        for (EntityMigrationColumnPlan columnPlan : plan.getColumnPlans()) {
            columns.add(columnPlan.getSourceColumn());
            if (!columnPlan.isStoredInSeparateTable()) {
                columns.add(columnPlan.getStorageColumn());
                if (StringUtils.isNotBlank(columnPlan.getAssistedQueryColumn())) {
                    columns.add(columnPlan.getAssistedQueryColumn());
                }
                if (StringUtils.isNotBlank(columnPlan.getLikeQueryColumn())) {
                    columns.add(columnPlan.getLikeQueryColumn());
                }
                if (StringUtils.isNotBlank(columnPlan.getMaskedColumn())) {
                    columns.add(columnPlan.getMaskedColumn());
                }
            }
            if (columnPlan.shouldWriteBackup()) {
                columns.add(columnPlan.getBackupColumn());
            }
        }
        StringBuilder sql = new StringBuilder("select ");
        int index = 0;
        for (String column : columns) {
            if (index++ > 0) {
                sql.append(", ");
            }
            sql.append(quote(column));
        }
        sql.append(" from ").append(quote(plan.getTableName())).append(" where ");
        appendCursorEqualityPredicate(sql, plan.getCursorColumns());
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int parameterIndex = 1;
            for (Object cursorValue : rowCursor.getValues().values()) {
                MigrationJdbcParameterBinder.bind(statement, parameterIndex++, cursorValue);
            }
            logCursorDebug("migration-load-current-row", sql.toString(), rowCursor);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new MigrationExecutionException(MigrationErrorCode.EXECUTION_FAILED,
                            "Missing current row while writing migration checkpoint: " + rowCursor,
                            null);
                }
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                for (String column : columns) {
                    row.put(column, resultSet.getObject(column));
                }
                return row;
            }
        }
    }

    private boolean isAlreadyInTargetState(Connection connection,
                                           EntityMigrationColumnPlan columnPlan,
                                           Map<String, Object> currentRow,
                                           Object plainValue,
                                           MigrationValueResolver.DerivedFieldValues derivedFieldValues)
            throws SQLException {
        if (columnPlan.shouldWriteBackup()
                && !valueEquals(currentRow.get(columnPlan.getBackupColumn()), plainValue)) {
            return false;
        }
        if (columnPlan.isStoredInSeparateTable()) {
            if (!valueEquals(currentRow.get(columnPlan.getSourceColumn()), derivedFieldValues.getHashValue())) {
                return false;
            }
            Map<String, Object> separateRow =
                    recordStateSupport.loadSeparateRow(connection, columnPlan, derivedFieldValues.getHashValue());
            if (separateRow == null) {
                return false;
            }
            return valueEquals(separateRow.get(columnPlan.getStorageColumn()), derivedFieldValues.getCipherText())
                    && valueEquals(separateRow.get(columnPlan.getAssistedQueryColumn()), derivedFieldValues.getHashValue())
                    && matchesOptionalValue(separateRow.get(columnPlan.getLikeQueryColumn()), derivedFieldValues.getLikeValue())
                    && matchesOptionalValue(separateRow.get(columnPlan.getMaskedColumn()), derivedFieldValues.getMaskedValue());
        }
        if (!valueEquals(currentRow.get(columnPlan.getStorageColumn()), derivedFieldValues.getCipherText())) {
            return false;
        }
        if (!matchesOptionalValue(currentRow.get(columnPlan.getAssistedQueryColumn()), derivedFieldValues.getHashValue())) {
            return false;
        }
        if (!matchesOptionalValue(currentRow.get(columnPlan.getLikeQueryColumn()), derivedFieldValues.getLikeValue())) {
            return false;
        }
        if (!matchesOptionalValue(currentRow.get(columnPlan.getMaskedColumn()), derivedFieldValues.getMaskedValue())) {
            return false;
        }
        if (!columnPlan.overwritesSourceColumn()) {
            return true;
        }
        return valueEquals(currentRow.get(columnPlan.getSourceColumn()),
                expectedSourceValue(columnPlan, derivedFieldValues));
    }

    private boolean isAlreadyMigratedWithoutPlaintext(Connection connection,
                                                      EntityMigrationColumnPlan columnPlan,
                                                      Map<String, Object> currentRow) throws SQLException {
        return recordStateSupport.isAlreadyMigratedWithoutPlaintext(connection, columnPlan, currentRow);
    }

    private void ensurePlaintextRecoverable(Connection connection,
                                            EntityMigrationPlan plan,
                                            EntityMigrationColumnPlan columnPlan,
                                            Map<String, Object> currentRow) throws SQLException {
        if (!recordStateSupport.isPlaintextIrrecoverable(connection, columnPlan, currentRow)) {
            return;
        }
        throw new MigrationExecutionException(MigrationErrorCode.PLAINTEXT_UNRECOVERABLE,
                recordStateSupport.buildPlaintextIrrecoverableMessage(plan.getTableName(), columnPlan), null);
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
                MigrationJdbcParameterBinder.bind(statement, parameterIndex++, value);
            }
            for (Object cursorValue : rowCursor.getValues().values()) {
                MigrationJdbcParameterBinder.bind(statement, parameterIndex++, cursorValue);
            }
            logCursorDebug("migration-update-main-row", sql.toString(), rowCursor);
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
        List<String> columns = new ArrayList<>();
        List<Object> bindValues = new ArrayList<>();
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
        if (columnPlan.hasDistinctMaskedColumn()) {
            columns.add(columnPlan.getMaskedColumn());
            bindValues.add(values.getMaskedValue());
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
                MigrationJdbcParameterBinder.bind(statement, index + 1, bindValues.get(index));
            }
            statement.executeUpdate();
        }
    }

    private String quote(String identifier) {
        return properties.getSqlDialect().quote(identifier);
    }

    private boolean valueEquals(Object actual, Object expected) {
        String actualText = actual == null ? null : String.valueOf(actual);
        String expectedText = expected == null ? null : String.valueOf(expected);
        return expectedText == null ? actualText == null : expectedText.equals(actualText);
    }

    private boolean matchesOptionalValue(Object actual, String expected) {
        return expected == null ? true : valueEquals(actual, expected);
    }

    private boolean isBlankValue(Object value) {
        return value == null || StringUtils.isBlank(String.valueOf(value));
    }

    private boolean matchesSameColumn(String left, String right) {
        return StringUtils.isNotBlank(left) && left.equals(right);
    }

    private String expectedSourceValue(EntityMigrationColumnPlan columnPlan,
                                       MigrationValueResolver.DerivedFieldValues derivedFieldValues) {
        if (matchesSameColumn(columnPlan.getSourceColumn(), columnPlan.getStorageColumn())) {
            return derivedFieldValues.getCipherText();
        }
        if (matchesSameColumn(columnPlan.getSourceColumn(), columnPlan.getAssistedQueryColumn())) {
            return derivedFieldValues.getHashValue();
        }
        if (matchesSameColumn(columnPlan.getSourceColumn(), columnPlan.getLikeQueryColumn())) {
            return derivedFieldValues.getLikeValue();
        }
        if (matchesSameColumn(columnPlan.getSourceColumn(), columnPlan.getMaskedColumn())) {
            return derivedFieldValues.getMaskedValue();
        }
        if (columnPlan.isStoredInSeparateTable()) {
            return derivedFieldValues.getHashValue();
        }
        return null;
    }

    private void logCursorDebug(String stage, String sql, MigrationCursor cursor) {
        if (!log.isDebugEnabled()) {
            return;
        }
        log.debug("Migration cursor stage={} sql={} cursor={}", stage, compactSql(sql), describeCursor(cursor));
    }

    private String describeCursor(MigrationCursor cursor) {
        if (cursor == null) {
            return "<null>";
        }
        StringBuilder builder = new StringBuilder("{");
        int index = 0;
        for (Map.Entry<String, Object> entry : cursor.getValues().entrySet()) {
            if (index++ > 0) {
                builder.append(", ");
            }
            Object value = entry.getValue();
            builder.append(entry.getKey()).append('=').append(value)
                    .append('(')
                    .append(value == null ? "null" : value.getClass().getSimpleName())
                    .append(')');
        }
        builder.append('}');
        return builder.toString();
    }

    private String compactSql(String sql) {
        return sql == null ? null : sql.replaceAll("\\s+", " ").trim();
    }
}
