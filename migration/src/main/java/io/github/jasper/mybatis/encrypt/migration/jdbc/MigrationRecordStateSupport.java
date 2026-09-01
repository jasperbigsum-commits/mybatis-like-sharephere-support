package io.github.jasper.mybatis.encrypt.migration.jdbc;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.migration.EntityMigrationColumnPlan;
import io.github.jasper.mybatis.encrypt.migration.EntityMigrationPlan;
import io.github.jasper.mybatis.encrypt.migration.MigrationErrorCode;
import io.github.jasper.mybatis.encrypt.migration.MigrationExecutionException;
import io.github.jasper.mybatis.encrypt.migration.MigrationVerificationException;
import io.github.jasper.mybatis.encrypt.migration.MigrationRecord;
import io.github.jasper.mybatis.encrypt.util.StringUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared migration-state helper used by writer and verifier.
 *
 * <p>This helper keeps the "already migrated without plaintext" rules in one place so
 * resumable execution and write-after verification observe the same state model.</p>
 *
 * <p>It also centralizes backup-column trust rules. A non-empty backup column is treated
 * as the preferred plaintext source only when the current source column is either still
 * equal to that plaintext or already matches one legal overwrite target derived from it.
 * This preserves resumable overwrite migrations while refusing obviously inconsistent
 * backup/source combinations.</p>
 */
final class MigrationRecordStateSupport {

    private final DatabaseEncryptionProperties properties;
    private final AlgorithmRegistry algorithmRegistry;

    MigrationRecordStateSupport(DatabaseEncryptionProperties properties, AlgorithmRegistry algorithmRegistry) {
        this.properties = properties;
        this.algorithmRegistry = algorithmRegistry;
    }

    /**
     * Resolve the plaintext that should drive rewrite and verification for one field.
     *
     * <p>When a backup column exists and already contains a non-blank value, the backup is
     * treated as the authoritative plaintext source. This is what allows overwrite-style
     * migrations to resume after the main-table source column has been replaced.</p>
     */
    Object resolvePlainValue(EntityMigrationColumnPlan columnPlan,
                             MigrationRecord record,
                             Map<String, Object> currentRow) {
        if (columnPlan.shouldWriteBackup()) {
            Object backupValue = currentRow.get(columnPlan.getBackupColumn());
            if (!isBlankValue(backupValue)) {
                return backupValue;
            }
        }
        return currentRow.containsKey(columnPlan.getSourceColumn())
                ? currentRow.get(columnPlan.getSourceColumn())
                : record.getColumnValue(columnPlan.getSourceColumn());
    }

    /**
     * Validate backup/source consistency before write-side mutation continues.
     *
     * <p>This method must allow normal resume states where the source column has already
     * been replaced by a legal derived target value, for example a hash or separate-table
     * reference, while still rejecting backup values that would cause future writes to be
     * derived from the wrong plaintext.</p>
     */
    void ensureBackupValueConsistentForWrite(EntityMigrationPlan plan,
                                             EntityMigrationColumnPlan columnPlan,
                                             Map<String, Object> currentRow,
                                             MigrationValueResolver.DerivedFieldValues derivedFromBackup) {
        ensureBackupValueConsistent(plan, columnPlan, currentRow, derivedFromBackup, true);
    }

    /**
     * Apply the same backup/source consistency rule during post-write verification.
     *
     * <p>Writer and verifier intentionally share one rule here so that a row accepted by
     * resumable write logic is not later rejected by verification for the same state.</p>
     */
    void ensureBackupValueConsistentForVerify(EntityMigrationPlan plan,
                                              EntityMigrationColumnPlan columnPlan,
                                              Map<String, Object> currentRow,
                                              MigrationValueResolver.DerivedFieldValues derivedFromBackup) {
        ensureBackupValueConsistent(plan, columnPlan, currentRow, derivedFromBackup, false);
    }

    boolean isAlreadyMigratedWithoutPlaintext(Connection connection,
                                              EntityMigrationColumnPlan columnPlan,
                                              Map<String, Object> currentRow) throws SQLException {
        if (!columnPlan.overwritesSourceColumn() || columnPlan.shouldWriteBackup()) {
            return false;
        }
        Object sourceValue = currentRow.get(columnPlan.getSourceColumn());
        if (isBlankValue(sourceValue)) {
            return false;
        }
        if (columnPlan.isStoredInSeparateTable()) {
            Map<String, Object> separateRow = loadSeparateRow(connection, columnPlan, String.valueOf(sourceValue));
            return separateRow != null
                    && !isBlankValue(separateRow.get(columnPlan.getStorageColumn()))
                    && optionalColumnPresent(separateRow.get(columnPlan.getLikeQueryColumn()), columnPlan.getLikeQueryColumn())
                    && optionalColumnPresent(separateRow.get(columnPlan.getMaskedColumn()), columnPlan.getMaskedColumn());
        }
        if (matchesSameColumn(columnPlan.getSourceColumn(), columnPlan.getStorageColumn())
                && isBlankValue(currentRow.get(columnPlan.getStorageColumn()))) {
            return false;
        }
        if (matchesSameColumn(columnPlan.getSourceColumn(), columnPlan.getAssistedQueryColumn())
                && !valueEquals(sourceValue, currentRow.get(columnPlan.getAssistedQueryColumn()))) {
            return false;
        }
        if (matchesSameColumn(columnPlan.getSourceColumn(), columnPlan.getLikeQueryColumn())
                && !valueEquals(sourceValue, currentRow.get(columnPlan.getLikeQueryColumn()))) {
            return false;
        }
        if (matchesSameColumn(columnPlan.getSourceColumn(), columnPlan.getMaskedColumn())
                && !valueEquals(sourceValue, currentRow.get(columnPlan.getMaskedColumn()))) {
            return false;
        }
        if (isBlankValue(currentRow.get(columnPlan.getStorageColumn()))) {
            return false;
        }
        if (!optionalColumnPresent(currentRow.get(columnPlan.getAssistedQueryColumn()), columnPlan.getAssistedQueryColumn())) {
            return false;
        }
        if (!optionalColumnPresent(currentRow.get(columnPlan.getLikeQueryColumn()), columnPlan.getLikeQueryColumn())) {
            return false;
        }
        return optionalColumnPresent(currentRow.get(columnPlan.getMaskedColumn()), columnPlan.getMaskedColumn());
    }

    boolean isPlaintextIrrecoverable(Connection connection,
                                     EntityMigrationColumnPlan columnPlan,
                                     Map<String, Object> currentRow) throws SQLException {
        if (!columnPlan.overwritesSourceColumn() || columnPlan.shouldWriteBackup()) {
            return false;
        }
        Object sourceValue = currentRow.get(columnPlan.getSourceColumn());
        if (isBlankValue(sourceValue) || isAlreadyMigratedWithoutPlaintext(connection, columnPlan, currentRow)) {
            return false;
        }
        if (columnPlan.isStoredInSeparateTable()) {
            return loadSeparateRow(connection, columnPlan, String.valueOf(sourceValue)) != null;
        }
        return hasDistinctDerivedSideEffects(columnPlan, currentRow);
    }

    String buildPlaintextIrrecoverableMessage(String tableName, EntityMigrationColumnPlan columnPlan) {
        StringBuilder message = new StringBuilder("Cannot continue migration for table ")
                .append(tableName)
                .append(" property ")
                .append(columnPlan.getProperty())
                .append(" because source column ")
                .append(tableName)
                .append('.')
                .append(columnPlan.getSourceColumn())
                .append(" no longer has recoverable plaintext and no backup column is configured. ");
        if (columnPlan.isStoredInSeparateTable()) {
            message.append("A previous partial separate-table migration already rewrote the main-table source value. ");
        } else {
            message.append("Derived same-table columns show that a previous overwrite-style migration already started. ");
        }
        message.append("Restore the original plaintext or add backupColumn(...) before running future overwrite migrations.");
        return message.toString();
    }

    Map<String, Object> loadSeparateRow(Connection connection,
                                        EntityMigrationColumnPlan columnPlan,
                                        String referenceHash) throws SQLException {
        if (referenceHash == null || StringUtils.isBlank(columnPlan.getAssistedQueryColumn())) {
            return null;
        }
        StringBuilder sql = new StringBuilder("select ")
                .append(quote(columnPlan.getStorageColumn()))
                .append(", ")
                .append(quote(columnPlan.getAssistedQueryColumn()));
        if (StringUtils.isNotBlank(columnPlan.getLikeQueryColumn())) {
            sql.append(", ").append(quote(columnPlan.getLikeQueryColumn()));
        }
        if (columnPlan.hasDistinctMaskedColumn()) {
            sql.append(", ").append(quote(columnPlan.getMaskedColumn()));
        }
        if (columnPlan.shouldWriteBackup()) {
            sql.append(", ").append(quote(columnPlan.getBackupColumn()));
        }
        sql.append(" from ").append(quote(columnPlan.getStorageTable()))
                .append(" where ").append(quote(columnPlan.getAssistedQueryColumn())).append(" = ?");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            statement.setString(1, referenceHash);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put(columnPlan.getStorageColumn(), resultSet.getObject(columnPlan.getStorageColumn()));
                row.put(columnPlan.getAssistedQueryColumn(), resultSet.getObject(columnPlan.getAssistedQueryColumn()));
                if (StringUtils.isNotBlank(columnPlan.getLikeQueryColumn())) {
                    row.put(columnPlan.getLikeQueryColumn(), resultSet.getObject(columnPlan.getLikeQueryColumn()));
                }
                if (StringUtils.isNotBlank(columnPlan.getMaskedColumn())) {
                    row.put(columnPlan.getMaskedColumn(), resultSet.getObject(columnPlan.getMaskedColumn()));
                }
                if (columnPlan.shouldWriteBackup()) {
                    row.put(columnPlan.getBackupColumn(), resultSet.getObject(columnPlan.getBackupColumn()));
                }
                return row;
            }
        }
    }

    private void ensureBackupValueConsistent(EntityMigrationPlan plan,
                                             EntityMigrationColumnPlan columnPlan,
                                             Map<String, Object> currentRow,
                                             MigrationValueResolver.DerivedFieldValues derivedFromBackup,
                                             boolean writePhase) {
        if (!columnPlan.shouldWriteBackup()) {
            return;
        }
        Object backupValue = currentRow.get(columnPlan.getBackupColumn());
        Object sourceValue = currentRow.get(columnPlan.getSourceColumn());
        if (isBlankValue(backupValue) || isBlankValue(sourceValue)) {
            return;
        }
        if (valueEquals(sourceValue, backupValue)) {
            return;
        }
        if (sourceValueMatches(columnPlan, backupValue, sourceValue, derivedFromBackup)) {
            return;
        }
        String message = buildBackupValueInconsistentMessage(plan, columnPlan, sourceValue, backupValue);
        if (writePhase) {
            throw new MigrationExecutionException(MigrationErrorCode.BACKUP_VALUE_INCONSISTENT, message, null);
        }
        throw new MigrationVerificationException(MigrationErrorCode.BACKUP_VALUE_INCONSISTENT, message);
    }

    private String buildBackupValueInconsistentMessage(EntityMigrationPlan plan,
                                                       EntityMigrationColumnPlan columnPlan,
                                                       Object sourceValue,
                                                       Object backupValue) {
        return "Cannot continue migration for table " + plan.getTableName()
                + " property " + columnPlan.getProperty()
                + " because backup column " + plan.getTableName() + '.' + columnPlan.getBackupColumn()
                + " does not match source column " + plan.getTableName() + '.' + columnPlan.getSourceColumn()
                + " and the source value is not in the expected overwrite target state. "
                + "source=" + sourceValue + ", backup=" + backupValue;
    }

    private String quote(String identifier) {
        return properties.getSqlDialect().quote(identifier);
    }

    private boolean valueEquals(Object actual, Object expected) {
        String actualText = actual == null ? null : String.valueOf(actual);
        String expectedText = expected == null ? null : String.valueOf(expected);
        return expectedText == null ? actualText == null : expectedText.equals(actualText);
    }

    private boolean optionalColumnPresent(Object actual, String columnName) {
        return StringUtils.isBlank(columnName) || !isBlankValue(actual);
    }

    private boolean hasDistinctDerivedSideEffects(EntityMigrationColumnPlan columnPlan, Map<String, Object> currentRow) {
        return hasDistinctDerivedValue(columnPlan.getSourceColumn(), columnPlan.getStorageColumn(),
                currentRow.get(columnPlan.getStorageColumn()))
                || hasDistinctDerivedValue(columnPlan.getSourceColumn(), columnPlan.getAssistedQueryColumn(),
                currentRow.get(columnPlan.getAssistedQueryColumn()))
                || hasDistinctDerivedValue(columnPlan.getSourceColumn(), columnPlan.getLikeQueryColumn(),
                currentRow.get(columnPlan.getLikeQueryColumn()))
                || hasDistinctDerivedValue(columnPlan.getSourceColumn(), columnPlan.getMaskedColumn(),
                currentRow.get(columnPlan.getMaskedColumn()));
    }

    private boolean hasDistinctDerivedValue(String sourceColumn, String targetColumn, Object targetValue) {
        return StringUtils.isNotBlank(targetColumn)
                && !matchesSameColumn(sourceColumn, targetColumn)
                && !isBlankValue(targetValue);
    }

    private boolean isBlankValue(Object value) {
        return value == null || StringUtils.isBlank(String.valueOf(value));
    }

    private boolean matchesSameColumn(String left, String right) {
        return StringUtils.isNotBlank(left) && left.equals(right);
    }

    private boolean cipherMatches(EntityMigrationColumnPlan columnPlan, Object plainValue, Object actualCipherValue) {
        String plainText = plainValue == null ? null : String.valueOf(plainValue);
        String actualCipher = actualCipherValue == null ? null : String.valueOf(actualCipherValue);
        if (plainText == null || actualCipher == null) {
            return plainText == null && actualCipher == null;
        }
        try {
            String decrypted = algorithmRegistry.cipher(columnPlan.getCipherAlgorithm()).decrypt(actualCipher);
            return plainText.equals(decrypted);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private boolean sourceValueMatches(EntityMigrationColumnPlan columnPlan,
                                       Object plainValue,
                                       Object actualSourceValue,
                                       MigrationValueResolver.DerivedFieldValues derivedFieldValues) {
        if (matchesSameColumn(columnPlan.getSourceColumn(), columnPlan.getStorageColumn())) {
            return cipherMatches(columnPlan, plainValue, actualSourceValue);
        }
        if (matchesSameColumn(columnPlan.getSourceColumn(), columnPlan.getAssistedQueryColumn())) {
            return valueEquals(actualSourceValue, derivedFieldValues.getHashValue());
        }
        if (matchesSameColumn(columnPlan.getSourceColumn(), columnPlan.getLikeQueryColumn())) {
            return valueEquals(actualSourceValue, derivedFieldValues.getLikeValue());
        }
        if (matchesSameColumn(columnPlan.getSourceColumn(), columnPlan.getMaskedColumn())) {
            return valueEquals(actualSourceValue, derivedFieldValues.getMaskedValue());
        }
        if (columnPlan.isStoredInSeparateTable()) {
            return valueEquals(actualSourceValue, derivedFieldValues.getHashValue());
        }
        return actualSourceValue == null;
    }
}
