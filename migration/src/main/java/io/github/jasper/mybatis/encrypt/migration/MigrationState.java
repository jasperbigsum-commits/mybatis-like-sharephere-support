package io.github.jasper.mybatis.encrypt.migration;

/**
 * Persisted resumable state for one entity migration task.
 */
public class MigrationState {

    private String entityName;
    private String tableName;
    private String idColumn;
    private String idJavaType;
    private MigrationStatus status = MigrationStatus.READY;
    private long totalRows;
    private String rangeStart;
    private String rangeEnd;
    private String lastProcessedId;
    private long scannedRows;
    private long migratedRows;
    private long skippedRows;
    private long verifiedRows;
    private boolean verificationEnabled;
    private String lastError;

    /**
     * Return the entity simple name.
     *
     * @return entity name
     */
    public String getEntityName() {
        return entityName;
    }

    /**
     * Set the entity simple name.
     *
     * @param entityName entity name
     */
    public void setEntityName(String entityName) {
        this.entityName = entityName;
    }

    /**
     * Return the main-table name.
     *
     * @return table name
     */
    public String getTableName() {
        return tableName;
    }

    /**
     * Set the main-table name.
     *
     * @param tableName table name
     */
    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    /**
     * Return the id column name.
     *
     * @return id column name
     */
    public String getIdColumn() {
        return idColumn;
    }

    /**
     * Set the id column name.
     *
     * @param idColumn id column name
     */
    public void setIdColumn(String idColumn) {
        this.idColumn = idColumn;
    }

    /**
     * Return the Java type name of the id column.
     *
     * @return id Java type name
     */
    public String getIdJavaType() {
        return idJavaType;
    }

    /**
     * Set the Java type name of the id column.
     *
     * @param idJavaType id Java type name
     */
    public void setIdJavaType(String idJavaType) {
        this.idJavaType = idJavaType;
    }

    /**
     * Return the current task status.
     *
     * @return migration status
     */
    public MigrationStatus getStatus() {
        return status;
    }

    /**
     * Set the current task status.
     *
     * @param status migration status
     */
    public void setStatus(MigrationStatus status) {
        this.status = status;
    }

    /**
     * Return the total row count in the task range.
     *
     * @return total rows
     */
    public long getTotalRows() {
        return totalRows;
    }

    /**
     * Set the total row count in the task range.
     *
     * @param totalRows total rows
     */
    public void setTotalRows(long totalRows) {
        this.totalRows = totalRows;
    }

    /**
     * Return the smallest id in the task range.
     *
     * @return range start value
     */
    public String getRangeStart() {
        return rangeStart;
    }

    /**
     * Set the smallest id in the task range.
     *
     * @param rangeStart range start value
     */
    public void setRangeStart(String rangeStart) {
        this.rangeStart = rangeStart;
    }

    /**
     * Return the greatest id in the task range.
     *
     * @return range end value
     */
    public String getRangeEnd() {
        return rangeEnd;
    }

    /**
     * Set the greatest id in the task range.
     *
     * @param rangeEnd range end value
     */
    public void setRangeEnd(String rangeEnd) {
        this.rangeEnd = rangeEnd;
    }

    /**
     * Return the latest committed id checkpoint.
     *
     * @return last processed id
     */
    public String getLastProcessedId() {
        return lastProcessedId;
    }

    /**
     * Set the latest committed id checkpoint.
     *
     * @param lastProcessedId last processed id
     */
    public void setLastProcessedId(String lastProcessedId) {
        this.lastProcessedId = lastProcessedId;
    }

    /**
     * Return the scanned row count.
     *
     * @return scanned row count
     */
    public long getScannedRows() {
        return scannedRows;
    }

    /**
     * Set the scanned row count.
     *
     * @param scannedRows scanned row count
     */
    public void setScannedRows(long scannedRows) {
        this.scannedRows = scannedRows;
    }

    /**
     * Return the migrated row count.
     *
     * @return migrated row count
     */
    public long getMigratedRows() {
        return migratedRows;
    }

    /**
     * Set the migrated row count.
     *
     * @param migratedRows migrated row count
     */
    public void setMigratedRows(long migratedRows) {
        this.migratedRows = migratedRows;
    }

    /**
     * Return the skipped row count.
     *
     * @return skipped row count
     */
    public long getSkippedRows() {
        return skippedRows;
    }

    /**
     * Set the skipped row count.
     *
     * @param skippedRows skipped row count
     */
    public void setSkippedRows(long skippedRows) {
        this.skippedRows = skippedRows;
    }

    /**
     * Return the verified row count.
     *
     * @return verified row count
     */
    public long getVerifiedRows() {
        return verifiedRows;
    }

    /**
     * Set the verified row count.
     *
     * @param verifiedRows verified row count
     */
    public void setVerifiedRows(long verifiedRows) {
        this.verifiedRows = verifiedRows;
    }

    /**
     * Return whether write-after verification is enabled.
     *
     * @return {@code true} when verification is enabled
     */
    public boolean isVerificationEnabled() {
        return verificationEnabled;
    }

    /**
     * Set whether write-after verification is enabled.
     *
     * @param verificationEnabled whether verification is enabled
     */
    public void setVerificationEnabled(boolean verificationEnabled) {
        this.verificationEnabled = verificationEnabled;
    }

    /**
     * Return the latest terminal error message.
     *
     * @return last error message, or {@code null}
     */
    public String getLastError() {
        return lastError;
    }

    /**
     * Set the latest terminal error message.
     *
     * @param lastError last error message
     */
    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    /**
     * Convert the mutable state snapshot to an immutable report view.
     *
     * @return immutable migration report
     */
    public MigrationReport toReport() {
        return new MigrationReport(entityName, tableName, status, totalRows, rangeStart, rangeEnd,
                lastProcessedId, scannedRows, migratedRows, skippedRows, verifiedRows);
    }
}
