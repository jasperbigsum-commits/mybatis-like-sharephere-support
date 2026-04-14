package io.github.jasper.mybatis.encrypt.migration;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Plaintext row snapshot read from the source table before mutation.
 */
public final class MigrationRecord {

    private final MigrationCursor cursor;
    private final Map<String, Object> columnValues;

    /**
     * Create one immutable source-row snapshot.
     *
     * @param cursor row cursor snapshot
     * @param columnValues plaintext source column values
     */
    public MigrationRecord(MigrationCursor cursor, Map<String, Object> columnValues) {
        this.cursor = cursor;
        this.columnValues = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(columnValues));
    }

    /**
     * Return the row cursor snapshot.
     *
     * @return row cursor snapshot
     */
    public MigrationCursor getCursor() {
        return cursor;
    }

    /**
     * Return the primary cursor value when one single cursor column is used.
     * Multi-column cursor records return the full {@link MigrationCursor} snapshot.
     *
     * @return row cursor value or cursor snapshot
     */
    public Object getCursorValue() {
        return cursor == null ? null : (cursor.isSingleColumn() ? cursor.getPrimaryValue() : cursor);
    }

    /**
     * Return the primary cursor value when one single cursor column is used.
     * Multi-column cursor records return the full {@link MigrationCursor} snapshot.
     *
     * @return row cursor value or cursor snapshot
     * @deprecated use {@link #getCursor()} or {@link #getCursorValue()}
     */
    @Deprecated
    public Object getId() {
        return getCursorValue();
    }

    /**
     * Return the immutable source column values.
     *
     * @return source column values
     */
    public Map<String, Object> getColumnValues() {
        return columnValues;
    }

    /**
     * Return one source column value by name.
     *
     * @param column source column name
     * @return source column value, or {@code null}
     */
    public Object getColumnValue(String column) {
        return columnValues.get(column);
    }
}
