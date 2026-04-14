package io.github.jasper.mybatis.encrypt.migration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cumulative task progress snapshot.
 */
public final class MigrationReport {

    private final String entityName;
    private final String tableName;
    private final MigrationStatus status;
    private final long totalRows;
    private final List<String> cursorColumns;
    private final List<String> rangeStartValues;
    private final List<String> rangeEndValues;
    private final List<String> lastProcessedCursorValues;
    private final String rangeStart;
    private final String rangeEnd;
    private final String lastProcessedCursor;
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
     * @param rangeStart smallest cursor in task range
     * @param rangeEnd greatest cursor in task range
     * @param lastProcessedCursor latest committed cursor checkpoint
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
                           String lastProcessedCursor,
                           long scannedRows,
                           long migratedRows,
                           long skippedRows,
                           long verifiedRows) {
        this(entityName, tableName, status, totalRows, Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), rangeStart, rangeEnd,
                lastProcessedCursor, scannedRows, migratedRows, skippedRows, verifiedRows);
    }

    /**
     * Create one immutable migration progress snapshot.
     *
     * @param entityName entity simple name
     * @param tableName main-table name
     * @param status current task status
     * @param totalRows total rows in task range
     * @param cursorColumns ordered cursor columns in the main table
     * @param rangeStartValues serialized smallest cursor values in task range
     * @param rangeEndValues serialized greatest cursor values in task range
     * @param lastProcessedCursorValues serialized latest committed cursor checkpoint
     * @param rangeStart smallest cursor in task range
     * @param rangeEnd greatest cursor in task range
     * @param lastProcessedCursor latest committed cursor checkpoint
     * @param scannedRows scanned row count
     * @param migratedRows migrated row count
     * @param skippedRows skipped row count
     * @param verifiedRows verified row count
     */
    public MigrationReport(String entityName,
                           String tableName,
                           MigrationStatus status,
                           long totalRows,
                           List<String> cursorColumns,
                           List<String> rangeStartValues,
                           List<String> rangeEndValues,
                           List<String> lastProcessedCursorValues,
                           String rangeStart,
                           String rangeEnd,
                           String lastProcessedCursor,
                           long scannedRows,
                           long migratedRows,
                           long skippedRows,
                           long verifiedRows) {
        this.entityName = entityName;
        this.tableName = tableName;
        this.status = status;
        this.totalRows = totalRows;
        this.cursorColumns = immutableCopy(cursorColumns);
        this.rangeStartValues = immutableCopy(rangeStartValues);
        this.rangeEndValues = immutableCopy(rangeEndValues);
        this.lastProcessedCursorValues = immutableCopy(lastProcessedCursorValues);
        this.rangeStart = rangeStart;
        this.rangeEnd = rangeEnd;
        this.lastProcessedCursor = lastProcessedCursor;
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
     * Return ordered cursor columns in the main table.
     *
     * @return immutable cursor columns
     */
    public List<String> getCursorColumns() {
        return cursorColumns;
    }

    /**
     * Return serialized smallest cursor values in the task range.
     *
     * @return immutable range-start cursor values
     */
    public List<String> getRangeStartValues() {
        return rangeStartValues;
    }

    /**
     * Return serialized greatest cursor values in the task range.
     *
     * @return immutable range-end cursor values
     */
    public List<String> getRangeEndValues() {
        return rangeEndValues;
    }

    /**
     * Return serialized latest committed cursor checkpoint values.
     *
     * @return immutable last-processed cursor values
     */
    public List<String> getLastProcessedCursorValues() {
        return lastProcessedCursorValues;
    }

    /**
     * Return the smallest cursor in the task range.
     *
     * @return range start value
     */
    public String getRangeStart() {
        return rangeStart;
    }

    /**
     * Return the greatest cursor in the task range.
     *
     * @return range end value
     */
    public String getRangeEnd() {
        return rangeEnd;
    }

    /**
     * Return the latest committed cursor checkpoint.
     *
     * @return last processed cursor
     */
    public String getLastProcessedCursor() {
        return lastProcessedCursor;
    }

    /**
     * Return the smallest cursor in the task range keyed by cursor column.
     *
     * @return immutable range-start cursor map
     */
    public Map<String, String> getRangeStartCursorMap() {
        return toCursorMap(rangeStartValues);
    }

    /**
     * Return the greatest cursor in the task range keyed by cursor column.
     *
     * @return immutable range-end cursor map
     */
    public Map<String, String> getRangeEndCursorMap() {
        return toCursorMap(rangeEndValues);
    }

    /**
     * Return the latest committed cursor checkpoint keyed by cursor column.
     *
     * @return immutable last-processed cursor map
     */
    public Map<String, String> getLastProcessedCursorMap() {
        return toCursorMap(lastProcessedCursorValues);
    }

    /**
     * Return the latest committed cursor checkpoint.
     *
     * @return last processed cursor
     * @deprecated use {@link #getLastProcessedCursor()}
     */
    @Deprecated
    public String getLastProcessedId() {
        return getLastProcessedCursor();
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

    private List<String> immutableCopy(List<String> values) {
        return values == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(values));
    }

    private Map<String, String> toCursorMap(List<String> values) {
        if (cursorColumns.isEmpty() || values.isEmpty() || cursorColumns.size() != values.size()) {
            return Collections.emptyMap();
        }
        Map<String, String> mappedValues = new LinkedHashMap<>();
        for (int index = 0; index < values.size(); index++) {
            mappedValues.put(cursorColumns.get(index), values.get(index));
        }
        return Collections.unmodifiableMap(mappedValues);
    }
}
