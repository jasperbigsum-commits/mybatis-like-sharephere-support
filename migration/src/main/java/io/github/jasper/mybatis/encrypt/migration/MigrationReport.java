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

    public String getEntityName() {
        return entityName;
    }

    public String getTableName() {
        return tableName;
    }

    public MigrationStatus getStatus() {
        return status;
    }

    public long getTotalRows() {
        return totalRows;
    }

    public String getRangeStart() {
        return rangeStart;
    }

    public String getRangeEnd() {
        return rangeEnd;
    }

    public String getLastProcessedId() {
        return lastProcessedId;
    }

    public long getScannedRows() {
        return scannedRows;
    }

    public long getMigratedRows() {
        return migratedRows;
    }

    public long getSkippedRows() {
        return skippedRows;
    }

    public long getVerifiedRows() {
        return verifiedRows;
    }
}
