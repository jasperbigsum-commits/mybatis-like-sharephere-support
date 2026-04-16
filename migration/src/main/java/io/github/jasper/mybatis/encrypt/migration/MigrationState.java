package io.github.jasper.mybatis.encrypt.migration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Persisted resumable state for one entity migration task.
 */
public class MigrationState {

    private String dataSourceName;
    private String entityName;
    private String tableName;
    private List<String> cursorColumns = Collections.emptyList();
    private List<String> cursorJavaTypes = Collections.emptyList();
    private MigrationStatus status = MigrationStatus.READY;
    private long totalRows;
    private List<String> rangeStartValues = Collections.emptyList();
    private List<String> rangeEndValues = Collections.emptyList();
    private List<String> lastProcessedCursorValues = Collections.emptyList();
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
     * Return the bound data source name.
     *
     * @return data source name
     */
    public String getDataSourceName() {
        return dataSourceName;
    }

    /**
     * Set the bound data source name.
     *
     * @param dataSourceName data source name
     */
    public void setDataSourceName(String dataSourceName) {
        this.dataSourceName = dataSourceName;
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
     * Return ordered cursor column names.
     *
     * @return immutable cursor columns
     */
    public List<String> getCursorColumns() {
        return cursorColumns;
    }

    /**
     * Set ordered cursor column names.
     *
     * @param cursorColumns ordered cursor columns
     */
    public void setCursorColumns(List<String> cursorColumns) {
        this.cursorColumns = immutableCopy(cursorColumns);
    }

    /**
     * Return the first cursor column name.
     *
     * @return first cursor column name
     */
    public String getCursorColumn() {
        return cursorColumns.isEmpty() ? null : cursorColumns.get(0);
    }

    /**
     * Set one single cursor column name.
     *
     * @param cursorColumn cursor column name
     */
    public void setCursorColumn(String cursorColumn) {
        this.cursorColumns = singletonOrEmpty(cursorColumn);
    }

    /**
     * Return Java type names of ordered cursor columns.
     *
     * @return immutable cursor Java type names
     */
    public List<String> getCursorJavaTypes() {
        return cursorJavaTypes;
    }

    /**
     * Set Java type names of ordered cursor columns.
     *
     * @param cursorJavaTypes ordered cursor Java type names
     */
    public void setCursorJavaTypes(List<String> cursorJavaTypes) {
        this.cursorJavaTypes = immutableCopy(cursorJavaTypes);
    }

    /**
     * Return the Java type name of the first cursor column.
     *
     * @return first cursor Java type name
     */
    public String getCursorJavaType() {
        return cursorJavaTypes.isEmpty() ? null : cursorJavaTypes.get(0);
    }

    /**
     * Set the Java type name of one single cursor column.
     *
     * @param cursorJavaType cursor Java type name
     */
    public void setCursorJavaType(String cursorJavaType) {
        this.cursorJavaTypes = singletonOrEmpty(cursorJavaType);
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
     * Return serialized range-start cursor values.
     *
     * @return immutable range-start cursor values
     */
    public List<String> getRangeStartValues() {
        return rangeStartValues;
    }

    /**
     * Set serialized range-start cursor values.
     *
     * @param rangeStartValues range-start cursor values
     */
    public void setRangeStartValues(List<String> rangeStartValues) {
        this.rangeStartValues = immutableCopy(rangeStartValues);
    }

    /**
     * Return serialized range-end cursor values.
     *
     * @return immutable range-end cursor values
     */
    public List<String> getRangeEndValues() {
        return rangeEndValues;
    }

    /**
     * Set serialized range-end cursor values.
     *
     * @param rangeEndValues range-end cursor values
     */
    public void setRangeEndValues(List<String> rangeEndValues) {
        this.rangeEndValues = immutableCopy(rangeEndValues);
    }

    /**
     * Return serialized last-processed cursor checkpoint values.
     *
     * @return immutable last-processed cursor values
     */
    public List<String> getLastProcessedCursorValues() {
        return lastProcessedCursorValues;
    }

    /**
     * Set serialized last-processed cursor checkpoint values.
     *
     * @param lastProcessedCursorValues last-processed cursor values
     */
    public void setLastProcessedCursorValues(List<String> lastProcessedCursorValues) {
        this.lastProcessedCursorValues = immutableCopy(lastProcessedCursorValues);
    }

    /**
     * Return the smallest cursor in the task range.
     *
     * @return range-start display value
     */
    public String getRangeStart() {
        return display(rangeStartValues);
    }

    /**
     * Set the smallest cursor in the task range for one single cursor column.
     *
     * @param rangeStart range-start display value
     */
    public void setRangeStart(String rangeStart) {
        this.rangeStartValues = singletonOrEmpty(rangeStart);
    }

    /**
     * Return the greatest cursor in the task range.
     *
     * @return range-end display value
     */
    public String getRangeEnd() {
        return display(rangeEndValues);
    }

    /**
     * Set the greatest cursor in the task range for one single cursor column.
     *
     * @param rangeEnd range-end display value
     */
    public void setRangeEnd(String rangeEnd) {
        this.rangeEndValues = singletonOrEmpty(rangeEnd);
    }

    /**
     * Return the latest committed cursor checkpoint.
     *
     * @return last processed cursor display value
     */
    public String getLastProcessedCursor() {
        return display(lastProcessedCursorValues);
    }

    /**
     * Set the latest committed cursor checkpoint for one single cursor column.
     *
     * @param lastProcessedCursor last processed cursor display value
     */
    public void setLastProcessedCursor(String lastProcessedCursor) {
        this.lastProcessedCursorValues = singletonOrEmpty(lastProcessedCursor);
    }

    /**
     * Return the first cursor column name.
     *
     * @return cursor column name
     * @deprecated use {@link #getCursorColumns()}
     */
    @Deprecated
    public String getIdColumn() {
        return getCursorColumn();
    }

    /**
     * Set one single cursor column name.
     *
     * @param idColumn cursor column name
     * @deprecated use {@link #setCursorColumns(List)}
     */
    @Deprecated
    public void setIdColumn(String idColumn) {
        setCursorColumn(idColumn);
    }

    /**
     * Return the Java type name of the first cursor column.
     *
     * @return first cursor Java type name
     * @deprecated use {@link #getCursorJavaTypes()}
     */
    @Deprecated
    public String getIdJavaType() {
        return getCursorJavaType();
    }

    /**
     * Set the Java type name of one single cursor column.
     *
     * @param idJavaType cursor Java type name
     * @deprecated use {@link #setCursorJavaTypes(List)}
     */
    @Deprecated
    public void setIdJavaType(String idJavaType) {
        setCursorJavaType(idJavaType);
    }

    /**
     * Return the latest committed cursor checkpoint.
     *
     * @return last processed cursor display value
     * @deprecated use {@link #getLastProcessedCursorValues()}
     */
    @Deprecated
    public String getLastProcessedId() {
        return getLastProcessedCursor();
    }

    /**
     * Set the latest committed cursor checkpoint for one single cursor column.
     *
     * @param lastProcessedId last processed cursor display value
     * @deprecated use {@link #setLastProcessedCursorValues(List)}
     */
    @Deprecated
    public void setLastProcessedId(String lastProcessedId) {
        setLastProcessedCursor(lastProcessedId);
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
     * Return whether verification is enabled.
     *
     * @return {@code true} when verification is enabled
     */
    public boolean isVerificationEnabled() {
        return verificationEnabled;
    }

    /**
     * Set whether verification is enabled.
     *
     * @param verificationEnabled verification flag
     */
    public void setVerificationEnabled(boolean verificationEnabled) {
        this.verificationEnabled = verificationEnabled;
    }

    /**
     * Return the latest error message.
     *
     * @return latest error message
     */
    public String getLastError() {
        return lastError;
    }

    /**
     * Set the latest error message.
     *
     * @param lastError latest error message
     */
    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    /**
     * Convert the persisted state to one report snapshot.
     *
     * @return migration report
     */
    public MigrationReport toReport() {
        return new MigrationReport(dataSourceName, entityName, tableName, status, totalRows, cursorColumns, rangeStartValues,
                rangeEndValues, lastProcessedCursorValues, getRangeStart(), getRangeEnd(), getLastProcessedCursor(),
                scannedRows, migratedRows, skippedRows, verifiedRows);
    }

    private List<String> immutableCopy(List<String> values) {
        return values == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(values));
    }

    private List<String> singletonOrEmpty(String value) {
        if (value == null) {
            return Collections.emptyList();
        }
        List<String> values = new ArrayList<>(1);
        values.add(value);
        return Collections.unmodifiableList(values);
    }

    private String display(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        if (values.size() == 1) {
            return values.get(0);
        }
        if (cursorColumns.size() == values.size()) {
            java.util.Map<String, String> mappedValues = new java.util.LinkedHashMap<>();
            for (int index = 0; index < values.size(); index++) {
                mappedValues.put(cursorColumns.get(index), values.get(index));
            }
            return mappedValues.toString();
        }
        return values.toString();
    }
}
