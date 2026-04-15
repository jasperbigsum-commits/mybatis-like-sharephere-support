package io.github.jasper.mybatis.encrypt.migration.jdbc;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.migration.EntityMigrationColumnPlan;
import io.github.jasper.mybatis.encrypt.migration.EntityMigrationPlan;
import io.github.jasper.mybatis.encrypt.migration.MigrationCursor;
import io.github.jasper.mybatis.encrypt.migration.MigrationErrorCode;
import io.github.jasper.mybatis.encrypt.migration.MigrationRecord;
import io.github.jasper.mybatis.encrypt.migration.MigrationRecordVerifier;
import io.github.jasper.mybatis.encrypt.migration.MigrationVerificationException;
import io.github.jasper.mybatis.encrypt.util.StringUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Default JDBC verifier that validates migrated values within the same transaction.
 */
public class JdbcMigrationRecordVerifier implements MigrationRecordVerifier {

    private final AlgorithmRegistry algorithmRegistry;
    private final DatabaseEncryptionProperties properties;
    private final MigrationValueResolver valueResolver;

    /**
     * jdbc记录迁移验证器
     * @param properties 配置
     * @param algorithmRegistry 加密算法
     */
    public JdbcMigrationRecordVerifier(DatabaseEncryptionProperties properties, AlgorithmRegistry algorithmRegistry) {
        this.algorithmRegistry = algorithmRegistry;
        this.properties = properties;
        this.valueResolver = new MigrationValueResolver(algorithmRegistry);
    }

    @Override
    public void verify(Connection connection, EntityMigrationPlan plan, MigrationRecord record) throws SQLException {
        Map<String, Object> mainRow = loadMainRow(connection, plan, record.getCursor());
        for (EntityMigrationColumnPlan columnPlan : plan.getColumnPlans()) {
            Object plainValue = record.getColumnValue(columnPlan.getSourceColumn());
            MigrationValueResolver.DerivedFieldValues expected = valueResolver.resolve(columnPlan, plainValue);
            if (expected.isEmpty()) {
                continue;
            }
            if (columnPlan.shouldWriteBackup()) {
                assertMatches(columnPlan.getProperty(), "backup", plainValue, mainRow.get(columnPlan.getBackupColumn()));
            }
            if (columnPlan.isStoredInSeparateTable()) {
                Object referenceId = mainRow.get(columnPlan.getSourceColumn());
                if (referenceId == null) {
                    throw new MigrationVerificationException(MigrationErrorCode.VERIFICATION_REFERENCE_MISSING,
                            "Missing separate-table reference id for field: " + columnPlan.getProperty());
                }
                assertMatches(columnPlan.getProperty(), "referenceHash", expected.getHashValue(), referenceId);
                Map<String, Object> externalRow = loadExternalRow(connection, columnPlan, referenceId);
                assertCipherMatches(columnPlan, plainValue,
                        externalRow.get(columnPlan.getStorageColumn()));
                if (StringUtils.isNotBlank(columnPlan.getAssistedQueryColumn())) {
                    assertMatches(columnPlan.getProperty(), "hash", expected.getHashValue(),
                            externalRow.get(columnPlan.getAssistedQueryColumn()));
                }
                if (StringUtils.isNotBlank(columnPlan.getLikeQueryColumn())) {
                    assertMatches(columnPlan.getProperty(), "like", expected.getLikeValue(),
                            externalRow.get(columnPlan.getLikeQueryColumn()));
                }
                continue;
            }
            assertCipherMatches(columnPlan, plainValue,
                    mainRow.get(columnPlan.getStorageColumn()));
            if (StringUtils.isNotBlank(columnPlan.getAssistedQueryColumn())) {
                assertMatches(columnPlan.getProperty(), "hash", expected.getHashValue(),
                        mainRow.get(columnPlan.getAssistedQueryColumn()));
            }
            if (StringUtils.isNotBlank(columnPlan.getLikeQueryColumn())) {
                assertMatches(columnPlan.getProperty(), "like", expected.getLikeValue(),
                        mainRow.get(columnPlan.getLikeQueryColumn()));
            }
        }
    }

    private void assertCipherMatches(EntityMigrationColumnPlan columnPlan, Object plainValue, Object actualCipherValue) {
        String plainText = plainValue == null ? null : String.valueOf(plainValue);
        String actualCipher = actualCipherValue == null ? null : String.valueOf(actualCipherValue);
        String decrypted = actualCipher == null ? null
                : algorithmRegistry.cipher(columnPlan.getCipherAlgorithm()).decrypt(actualCipher);
        if (plainText == null ? decrypted != null : !plainText.equals(decrypted)) {
            throw new MigrationVerificationException(MigrationErrorCode.VERIFICATION_VALUE_MISMATCH,
                    "Verification failed for property " + columnPlan.getProperty() + " aspect cipher");
        }
    }

    private Map<String, Object> loadMainRow(Connection connection, EntityMigrationPlan plan, MigrationCursor rowCursor)
            throws SQLException {
        Set<String> columns = new LinkedHashSet<>();
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
        sql.append(" from ").append(quote(plan.getTableName()))
                .append(" where ");
        appendCursorEqualityPredicate(sql, plan.getCursorColumns());
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int parameterIndex = 1;
            for (Object cursorValue : rowCursor.getValues().values()) {
                statement.setObject(parameterIndex++, cursorValue);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new MigrationVerificationException(MigrationErrorCode.VERIFICATION_MAIN_ROW_MISSING,
                            "Missing migrated row in main table: " + rowCursor);
                }
                Map<String, Object> row = new LinkedHashMap<>();
                for (String column : columns) {
                    row.put(column, resultSet.getObject(column));
                }
                return row;
            }
        }
    }

    private Map<String, Object> loadExternalRow(Connection connection, EntityMigrationColumnPlan columnPlan, Object referenceId)
            throws SQLException {
        StringBuilder sql = new StringBuilder("select ").append(quote(columnPlan.getStorageColumn()));
        if (StringUtils.isNotBlank(columnPlan.getAssistedQueryColumn())) {
            sql.append(", ").append(quote(columnPlan.getAssistedQueryColumn()));
        }
        if (StringUtils.isNotBlank(columnPlan.getLikeQueryColumn())) {
            sql.append(", ").append(quote(columnPlan.getLikeQueryColumn()));
        }
        sql.append(" from ").append(quote(columnPlan.getStorageTable()))
                .append(" where ").append(quote(columnPlan.getAssistedQueryColumn())).append(" = ?");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            statement.setObject(1, referenceId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new MigrationVerificationException(MigrationErrorCode.VERIFICATION_EXTERNAL_ROW_MISSING,
                            "Missing separate-table row for reference id: " + referenceId);
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put(columnPlan.getStorageColumn(), resultSet.getObject(columnPlan.getStorageColumn()));
                if (StringUtils.isNotBlank(columnPlan.getAssistedQueryColumn())) {
                    row.put(columnPlan.getAssistedQueryColumn(),
                            resultSet.getObject(columnPlan.getAssistedQueryColumn()));
                }
                if (StringUtils.isNotBlank(columnPlan.getLikeQueryColumn())) {
                    row.put(columnPlan.getLikeQueryColumn(), resultSet.getObject(columnPlan.getLikeQueryColumn()));
                }
                return row;
            }
        }
    }

    private void assertMatches(String property, String aspect, Object expected, Object actual) {
        String expectedText = expected == null ? null : String.valueOf(expected);
        String actualText = actual == null ? null : String.valueOf(actual);
        if (expectedText == null ? actualText != null : !expectedText.equals(actualText)) {
            throw new MigrationVerificationException(MigrationErrorCode.VERIFICATION_VALUE_MISMATCH,
                    "Verification failed for property " + property + " aspect " + aspect);
        }
    }

    private void appendCursorEqualityPredicate(StringBuilder sql, java.util.List<String> cursorColumns) {
        for (int index = 0; index < cursorColumns.size(); index++) {
            if (index > 0) {
                sql.append(" and ");
            }
            sql.append(quote(cursorColumns.get(index))).append(" = ?");
        }
    }

    private String quote(String identifier) {
        return properties.getSqlDialect().quote(identifier);
    }
}
