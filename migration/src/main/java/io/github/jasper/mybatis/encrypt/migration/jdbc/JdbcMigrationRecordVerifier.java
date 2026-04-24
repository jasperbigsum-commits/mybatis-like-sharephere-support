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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default JDBC verifier that validates migrated values within the same transaction.
 */
public class JdbcMigrationRecordVerifier implements MigrationRecordVerifier {

    private static final Logger log = LoggerFactory.getLogger(JdbcMigrationRecordVerifier.class);

    private final AlgorithmRegistry algorithmRegistry;
    private final DatabaseEncryptionProperties properties;
    private final MigrationValueResolver valueResolver;
    private final MigrationRecordStateSupport recordStateSupport;

    /**
     * jdbc记录迁移验证器
     * @param properties 配置
     * @param algorithmRegistry 加密算法
     */
    public JdbcMigrationRecordVerifier(DatabaseEncryptionProperties properties, AlgorithmRegistry algorithmRegistry) {
        this.algorithmRegistry = algorithmRegistry;
        this.properties = properties;
        this.valueResolver = new MigrationValueResolver(algorithmRegistry);
        this.recordStateSupport = new MigrationRecordStateSupport(properties, algorithmRegistry);
    }

    @Override
    public void verify(Connection connection, EntityMigrationPlan plan, MigrationRecord record) throws SQLException {
        Map<String, Object> mainRow = loadMainRow(connection, plan, record.getCursor());
        for (EntityMigrationColumnPlan columnPlan : plan.getColumnPlans()) {
            if (recordStateSupport.isAlreadyMigratedWithoutPlaintext(connection, columnPlan, mainRow)) {
                continue;
            }
            ensurePlaintextRecoverable(connection, plan, columnPlan, mainRow);
            Object backupValue = columnPlan.shouldWriteBackup() ? mainRow.get(columnPlan.getBackupColumn()) : null;
            if (columnPlan.shouldWriteBackup()
                    && backupValue != null
                    && !StringUtils.isBlank(String.valueOf(backupValue))) {
                // Verification must honor the same backup trust boundary as write-side recovery.
                // Otherwise resumable overwrite states could pass write() but fail verify() for the same row.
                recordStateSupport.ensureBackupValueConsistentForVerify(
                        plan,
                        columnPlan,
                        mainRow,
                        valueResolver.resolve(columnPlan, backupValue));
            }
            Object plainValue = recordStateSupport.resolvePlainValue(columnPlan, record, mainRow);
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
                Map<String, Object> externalRow =
                        recordStateSupport.loadSeparateRow(connection, columnPlan, String.valueOf(referenceId));
                if (externalRow == null) {
                    throw new MigrationVerificationException(MigrationErrorCode.VERIFICATION_EXTERNAL_ROW_MISSING,
                            "Missing separate-table row for reference id: " + referenceId);
                }
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
                if (StringUtils.isNotBlank(columnPlan.getMaskedColumn())) {
                    assertMatches(columnPlan.getProperty(), "masked", expected.getMaskedValue(),
                            externalRow.get(columnPlan.getMaskedColumn()));
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
            if (StringUtils.isNotBlank(columnPlan.getMaskedColumn())) {
                assertMatches(columnPlan.getProperty(), "masked", expected.getMaskedValue(),
                        mainRow.get(columnPlan.getMaskedColumn()));
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
        sql.append(" from ").append(quote(plan.getTableName()))
                .append(" where ");
        appendCursorEqualityPredicate(sql, plan.getCursorColumns());
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int parameterIndex = 1;
            for (Object cursorValue : rowCursor.getValues().values()) {
                MigrationJdbcParameterBinder.bind(statement, parameterIndex++, cursorValue);
            }
            logCursorDebug("migration-verify-main-row", sql.toString(), rowCursor);
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

    private void assertMatches(String property, String aspect, Object expected, Object actual) {
        String expectedText = expected == null ? null : String.valueOf(expected);
        String actualText = actual == null ? null : String.valueOf(actual);
        if (expectedText == null ? actualText != null : !expectedText.equals(actualText)) {
            throw new MigrationVerificationException(MigrationErrorCode.VERIFICATION_VALUE_MISMATCH,
                    "Verification failed for property " + property + " aspect " + aspect);
        }
    }

    private void ensurePlaintextRecoverable(Connection connection,
                                            EntityMigrationPlan plan,
                                            EntityMigrationColumnPlan columnPlan,
                                            Map<String, Object> currentRow) throws SQLException {
        if (!recordStateSupport.isPlaintextIrrecoverable(connection, columnPlan, currentRow)) {
            return;
        }
        throw new MigrationVerificationException(MigrationErrorCode.PLAINTEXT_UNRECOVERABLE,
                recordStateSupport.buildPlaintextIrrecoverableMessage(plan.getTableName(), columnPlan));
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
