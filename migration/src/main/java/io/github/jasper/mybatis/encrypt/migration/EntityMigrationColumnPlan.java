package io.github.jasper.mybatis.encrypt.migration;

import io.github.jasper.mybatis.encrypt.util.NameUtils;
import io.github.jasper.mybatis.encrypt.util.StringUtils;

/**
 * Column-level mapping between plaintext source and encrypted storage fields.
 */
public final class EntityMigrationColumnPlan {

    private final String property;
    private final String sourceColumn;
    private final String storageColumn;
    private final String assistedQueryColumn;
    private final String likeQueryColumn;
    private final String maskedColumn;
    private final String cipherAlgorithm;
    private final String assistedQueryAlgorithm;
    private final String likeQueryAlgorithm;
    private final String maskedAlgorithm;
    private final boolean storedInSeparateTable;
    private final String storageTable;
    private final String storageIdColumn;
    private final String backupColumn;

    /**
     * Create one immutable column migration plan.
     *
     * @param property entity property name
     * @param sourceColumn plaintext source column in the main table
     * @param storageColumn cipher storage column
     * @param assistedQueryColumn deterministic hash column for equality lookup and separate-table linkage
     * @param likeQueryColumn optional LIKE lookup column
     * @param maskedColumn optional stored masked column
     * @param cipherAlgorithm cipher algorithm bean name
     * @param assistedQueryAlgorithm assisted query algorithm bean name
     * @param likeQueryAlgorithm like query algorithm bean name
     * @param maskedAlgorithm stored masked algorithm bean name
     * @param storedInSeparateTable whether the field uses separate-table storage
     * @param storageTable separate-table name when enabled
     * @param storageIdColumn physical primary key column in the separate table
     * @param backupColumn optional plaintext backup column in the main table
     */
    public EntityMigrationColumnPlan(String property,
                                     String sourceColumn,
                                     String storageColumn,
                                     String assistedQueryColumn,
                                     String likeQueryColumn,
                                     String maskedColumn,
                                     String cipherAlgorithm,
                                     String assistedQueryAlgorithm,
                                     String likeQueryAlgorithm,
                                     String maskedAlgorithm,
                                     boolean storedInSeparateTable,
                                     String storageTable,
                                     String storageIdColumn,
                                     String backupColumn) {
        this.property = property;
        this.sourceColumn = sourceColumn;
        this.storageColumn = storageColumn;
        this.assistedQueryColumn = assistedQueryColumn;
        this.likeQueryColumn = likeQueryColumn;
        this.maskedColumn = maskedColumn;
        this.cipherAlgorithm = cipherAlgorithm;
        this.assistedQueryAlgorithm = assistedQueryAlgorithm;
        this.likeQueryAlgorithm = likeQueryAlgorithm;
        this.maskedAlgorithm = maskedAlgorithm;
        this.storedInSeparateTable = storedInSeparateTable;
        this.storageTable = storageTable;
        this.storageIdColumn = storageIdColumn;
        this.backupColumn = backupColumn;
    }

    /**
     * Return the entity property name.
     *
     * @return entity property name
     */
    public String getProperty() {
        return property;
    }

    /**
     * Return the plaintext source column in the main table.
     *
     * @return source column name
     */
    public String getSourceColumn() {
        return sourceColumn;
    }

    /**
     * Return the physical cipher storage column.
     *
     * @return cipher storage column
     */
    public String getStorageColumn() {
        return storageColumn;
    }

    /**
     * Return the deterministic hash column used for equality lookup and separate-table linkage.
     *
     * @return assisted query column
     */
    public String getAssistedQueryColumn() {
        return assistedQueryColumn;
    }

    /**
     * Return the optional LIKE lookup column.
     *
     * @return like lookup column, or {@code null}
     */
    public String getLikeQueryColumn() {
        return likeQueryColumn;
    }

    /**
     * Return the optional stored masked column.
     *
     * @return masked column, or {@code null}
     */
    public String getMaskedColumn() {
        return maskedColumn;
    }

    /**
     * Return the cipher algorithm bean name.
     *
     * @return cipher algorithm bean name
     */
    public String getCipherAlgorithm() {
        return cipherAlgorithm;
    }

    /**
     * Return the assisted query algorithm bean name.
     *
     * @return assisted query algorithm bean name
     */
    public String getAssistedQueryAlgorithm() {
        return assistedQueryAlgorithm;
    }

    /**
     * Return the LIKE query algorithm bean name.
     *
     * @return like query algorithm bean name
     */
    public String getLikeQueryAlgorithm() {
        return likeQueryAlgorithm;
    }

    /**
     * Return the stored masked algorithm bean name.
     *
     * @return stored masked algorithm bean name
     */
    public String getMaskedAlgorithm() {
        return maskedAlgorithm;
    }

    /**
     * Return the effective stored masked algorithm.
     *
     * <p>When the LIKE query column and masked column share the same physical column,
     * migration must generate one value only, so the LIKE algorithm owns that column.</p>
     *
     * @return effective stored masked algorithm bean name
     */
    public String getEffectiveMaskedAlgorithm() {
        return sharesLikeQueryAndMaskedColumn() ? likeQueryAlgorithm : maskedAlgorithm;
    }

    /**
     * Return whether LIKE query and masked values share one physical column.
     *
     * @return {@code true} when both roles point to the same column
     */
    public boolean sharesLikeQueryAndMaskedColumn() {
        return sameColumn(likeQueryColumn, maskedColumn);
    }

    /**
     * Return whether the masked column needs an independent write target.
     *
     * @return {@code true} when masked column is configured and not shared with LIKE column
     */
    public boolean hasDistinctMaskedColumn() {
        return StringUtils.isNotBlank(maskedColumn) && !sharesLikeQueryAndMaskedColumn();
    }

    /**
     * Return whether the field uses separate-table storage.
     *
     * @return {@code true} when stored in a separate table
     */
    public boolean isStoredInSeparateTable() {
        return storedInSeparateTable;
    }

    /**
     * Return the separate-table name when enabled.
     *
     * @return separate-table name, or {@code null}
     */
    public String getStorageTable() {
        return storageTable;
    }

    /**
     * Return the physical primary key column in the separate table.
     *
     * @return separate-table primary key column
     */
    public String getStorageIdColumn() {
        return storageIdColumn;
    }

    /**
     * Return the optional plaintext backup column in the main table.
     *
     * @return backup column, or {@code null}
     */
    public String getBackupColumn() {
        return backupColumn;
    }

    /**
     * Return whether migration overwrites the plaintext source column.
     *
     * @return {@code true} when source plaintext will be replaced
     */
    public boolean overwritesSourceColumn() {
        return storedInSeparateTable
                || matchesSourceColumn(storageColumn)
                || matchesSourceColumn(assistedQueryColumn)
                || matchesSourceColumn(likeQueryColumn)
                || matchesSourceColumn(maskedColumn);
    }

    private boolean matchesSourceColumn(String targetColumn) {
        return sourceColumn.equals(targetColumn);
    }

    private boolean sameColumn(String left, String right) {
        String normalizedLeft = NameUtils.normalizeIdentifier(left);
        String normalizedRight = NameUtils.normalizeIdentifier(right);
        return normalizedLeft != null && normalizedLeft.equals(normalizedRight);
    }

    /**
     * Return whether migration should write a plaintext backup column.
     *
     * @return {@code true} when backup is configured and source is overwritten
     */
    public boolean shouldWriteBackup() {
        return backupColumn != null && !backupColumn.trim().isEmpty() && overwritesSourceColumn();
    }

    /**
     * Return whether the given main-table column will be mutated during migration.
     *
     * @param column main-table column name
     * @return {@code true} when the column is used as one write target during migration
     */
    public boolean mutatesMainTableColumn(String column) {
        String normalized = NameUtils.normalizeIdentifier(column);
        if (normalized == null) {
            return false;
        }
        if (overwritesSourceColumn()
                && normalized.equals(NameUtils.normalizeIdentifier(sourceColumn))) {
            return true;
        }
        if (shouldWriteBackup()
                && normalized.equals(NameUtils.normalizeIdentifier(backupColumn))) {
            return true;
        }
        if (!storedInSeparateTable
                && normalized.equals(NameUtils.normalizeIdentifier(storageColumn))) {
            return true;
        }
        if (!storedInSeparateTable
                && StringUtils.isNotBlank(assistedQueryColumn)
                && normalized.equals(NameUtils.normalizeIdentifier(assistedQueryColumn))) {
            return true;
        }
        if (!storedInSeparateTable
                && StringUtils.isNotBlank(likeQueryColumn)
                && normalized.equals(NameUtils.normalizeIdentifier(likeQueryColumn))) {
            return true;
        }
        return !storedInSeparateTable
                && StringUtils.isNotBlank(maskedColumn)
                && normalized.equals(NameUtils.normalizeIdentifier(maskedColumn));
    }
}
