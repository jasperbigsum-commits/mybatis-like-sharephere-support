package io.github.jasper.mybatis.encrypt.migration;

/**
 * Table range metadata used for progress persistence.
 */
public final class MigrationRange {

    private final long totalRows;
    private final Object rangeStart;
    private final Object rangeEnd;
    private final String idJavaType;

    /**
     * Create one immutable range snapshot.
     *
     * @param totalRows total rows in the current task range
     * @param rangeStart smallest id value in range
     * @param rangeEnd greatest id value in range
     * @param idJavaType Java type name used for the id column
     */
    public MigrationRange(long totalRows, Object rangeStart, Object rangeEnd, String idJavaType) {
        this.totalRows = totalRows;
        this.rangeStart = rangeStart;
        this.rangeEnd = rangeEnd;
        this.idJavaType = idJavaType;
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
     * Return the smallest id value in range.
     *
     * @return range start value
     */
    public Object getRangeStart() {
        return rangeStart;
    }

    /**
     * Return the greatest id value in range.
     *
     * @return range end value
     */
    public Object getRangeEnd() {
        return rangeEnd;
    }

    /**
     * Return the Java type name used for the id column.
     *
     * @return id Java type name
     */
    public String getIdJavaType() {
        return idJavaType;
    }
}
