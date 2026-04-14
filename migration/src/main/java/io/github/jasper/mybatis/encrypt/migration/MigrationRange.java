package io.github.jasper.mybatis.encrypt.migration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Table range metadata used for progress persistence.
 */
public final class MigrationRange {

    private final long totalRows;
    private final MigrationCursor rangeStart;
    private final MigrationCursor rangeEnd;
    private final List<String> cursorJavaTypes;

    /**
     * Create one immutable range snapshot.
     *
     * @param totalRows total rows in the current task range
     * @param rangeStart smallest cursor in range
     * @param rangeEnd greatest cursor in range
     * @param cursorJavaTypes Java type names used for ordered cursor columns
     */
    public MigrationRange(long totalRows,
                          MigrationCursor rangeStart,
                          MigrationCursor rangeEnd,
                          List<String> cursorJavaTypes) {
        this.totalRows = totalRows;
        this.rangeStart = rangeStart;
        this.rangeEnd = rangeEnd;
        this.cursorJavaTypes = cursorJavaTypes == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(cursorJavaTypes));
    }

    /**
     * Return total rows in the current task range.
     *
     * @return total rows
     */
    public long getTotalRows() {
        return totalRows;
    }

    /**
     * Return the smallest cursor in range.
     *
     * @return range start cursor
     */
    public MigrationCursor getRangeStartCursor() {
        return rangeStart;
    }

    /**
     * Return the greatest cursor in range.
     *
     * @return range end cursor
     */
    public MigrationCursor getRangeEndCursor() {
        return rangeEnd;
    }

    /**
     * Return Java type names used for the ordered cursor columns.
     *
     * @return immutable cursor Java type names
     */
    public List<String> getCursorJavaTypes() {
        return cursorJavaTypes;
    }

    /**
     * Return the smallest cursor in range.
     *
     * @return range start display value
     */
    public Object getRangeStart() {
        return rangeStart == null ? null : (rangeStart.isSingleColumn() ? rangeStart.getPrimaryValue() : rangeStart);
    }

    /**
     * Return the greatest cursor in range.
     *
     * @return range end display value
     */
    public Object getRangeEnd() {
        return rangeEnd == null ? null : (rangeEnd.isSingleColumn() ? rangeEnd.getPrimaryValue() : rangeEnd);
    }

    /**
     * Return the Java type name used for the first cursor column.
     *
     * @return first cursor Java type name
     */
    public String getCursorJavaType() {
        return cursorJavaTypes.isEmpty() ? null : cursorJavaTypes.get(0);
    }

    /**
     * Return the Java type name used for the first cursor column.
     *
     * @return first cursor Java type name
     * @deprecated use {@link #getCursorJavaTypes()}
     */
    @Deprecated
    public String getIdJavaType() {
        return getCursorJavaType();
    }
}
