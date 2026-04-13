package io.github.jasper.mybatis.encrypt.migration;

/**
 * Cumulative task progress snapshot.
 */
public final class MigrationReport {

    private final String entityName;
    private final String tableName;
    private final MigrationStatus status;
    private final long totalRows;
    private final String rangeStart;
    private final String rangeEnd;
    private final String lastProcessedId;
    private final long scannedRows;
    private final long migratedRows;
    private final long skippedRows;
    private final long verifiedRows;

    /**
     * Create one immutable migration progress snapshot.
     *
     * @param entityName entity simple name
     * @param tableName main-table name
     * @param status current task status
     * @param totalRows total rows in task range
     * @param rangeStart smallest id in task range
     * @param rangeEnd greatest id in task range
     * @param lastProcessedId latest committed id checkpoint
     * @param scannedRows scanned row count
     * @param migratedRows migrated row count
     * @param skippedRows skipped row count
     * @param verifiedRows verified row count
     */
    public MigrationReport(String entityName,
                           String tableName,
                           MigrationStatus status,
                           long totalRows,
                           String rangeStart,
                           String rangeEnd,
                           String lastProcessedId,
                           long scannedRows,
                           long migratedRows,
                           long skippedRows,
                           long verifiedRows) {
        this.entityName = entityName;
        this.tableName = tableName;
        this.status = status;
        this.totalRows = totalRows;
        this.rangeStart = rangeStart;
        this.rangeEnd = rangeEnd;
        this.lastProcessedId = lastProcessedId;
        this.scannedRows = scannedRows;
        this.migratedRows = migratedRows;
        this.skippedRows = skippedRows;
        this.verifiedRows = verifiedRows;
    }

    /**
     * Return the entity simple name.
     *
     * @return entity name
     */
    public String getEntityName() {
        return entityName;
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
     * Return the current task status.
     *
     * @return migration status
     */
    public MigrationStatus getStatus() {
        return status;
    }

    /**
     * Return total rows in the task range.
     *
     * @return total rows
     */
    public long getTotalRows() {
        return totalRows;
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
     * Return the greatest id in the task range.
     *
     * @return range end value
     */
    public String getRangeEnd() {
        return rangeEnd;
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
     * Return the scanned row count.
     *
     * @return scanned row count
     */
    public long getScannedRows() {
        return scannedRows;
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
     * Return the skipped row count.
     *
     * @return skipped row count
     */
    public long getSkippedRows() {
        return skippedRows;
    }

    /**
     * Return the verified row count.
     *
     * @return verified row count
     */
    public long getVerifiedRows() {
        return verifiedRows;
    }
}
