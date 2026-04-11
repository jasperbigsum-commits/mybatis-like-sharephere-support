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

    public String getEntityName() {
        return entityName;
    }

    public void setEntityName(String entityName) {
        this.entityName = entityName;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getIdColumn() {
        return idColumn;
    }

    public void setIdColumn(String idColumn) {
        this.idColumn = idColumn;
    }

    public String getIdJavaType() {
        return idJavaType;
    }

    public void setIdJavaType(String idJavaType) {
        this.idJavaType = idJavaType;
    }

    public MigrationStatus getStatus() {
        return status;
    }

    public void setStatus(MigrationStatus status) {
        this.status = status;
    }

    public long getTotalRows() {
        return totalRows;
    }

    public void setTotalRows(long totalRows) {
        this.totalRows = totalRows;
    }

    public String getRangeStart() {
        return rangeStart;
    }

    public void setRangeStart(String rangeStart) {
        this.rangeStart = rangeStart;
    }

    public String getRangeEnd() {
        return rangeEnd;
    }

    public void setRangeEnd(String rangeEnd) {
        this.rangeEnd = rangeEnd;
    }

    public String getLastProcessedId() {
        return lastProcessedId;
    }

    public void setLastProcessedId(String lastProcessedId) {
        this.lastProcessedId = lastProcessedId;
    }

    public long getScannedRows() {
        return scannedRows;
    }

    public void setScannedRows(long scannedRows) {
        this.scannedRows = scannedRows;
    }

    public long getMigratedRows() {
        return migratedRows;
    }

    public void setMigratedRows(long migratedRows) {
        this.migratedRows = migratedRows;
    }

    public long getSkippedRows() {
        return skippedRows;
    }

    public void setSkippedRows(long skippedRows) {
        this.skippedRows = skippedRows;
    }

    public long getVerifiedRows() {
        return verifiedRows;
    }

    public void setVerifiedRows(long verifiedRows) {
        this.verifiedRows = verifiedRows;
    }

    public boolean isVerificationEnabled() {
        return verificationEnabled;
    }

    public void setVerificationEnabled(boolean verificationEnabled) {
        this.verificationEnabled = verificationEnabled;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public MigrationReport toReport() {
        return new MigrationReport(entityName, tableName, status, totalRows, rangeStart, rangeEnd,
                lastProcessedId, scannedRows, migratedRows, skippedRows, verifiedRows);
    }
}
