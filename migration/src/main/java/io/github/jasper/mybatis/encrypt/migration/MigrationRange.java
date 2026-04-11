package io.github.jasper.mybatis.encrypt.migration;

/**
 * Table range metadata used for progress persistence.
 */
public final class MigrationRange {

    private final long totalRows;
    private final Object rangeStart;
    private final Object rangeEnd;
    private final String idJavaType;

    public MigrationRange(long totalRows, Object rangeStart, Object rangeEnd, String idJavaType) {
        this.totalRows = totalRows;
        this.rangeStart = rangeStart;
        this.rangeEnd = rangeEnd;
        this.idJavaType = idJavaType;
    }

    public long getTotalRows() {
        return totalRows;
    }

    public Object getRangeStart() {
        return rangeStart;
    }

    public Object getRangeEnd() {
        return rangeEnd;
    }

    public String getIdJavaType() {
        return idJavaType;
    }
}
