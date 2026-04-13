package io.github.jasper.mybatis.encrypt.migration;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Plaintext row snapshot read from the source table before mutation.
 */
public final class MigrationRecord {

    private final Object id;
    private final Map<String, Object> columnValues;

    /**
     * Create one immutable source-row snapshot.
     *
     * @param id row primary key value
     * @param columnValues plaintext source column values
     */
    public MigrationRecord(Object id, Map<String, Object> columnValues) {
        this.id = id;
        this.columnValues = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(columnValues));
    }

    /**
     * Return the source row primary key value.
     *
     * @return row id
     */
    public Object getId() {
        return id;
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
