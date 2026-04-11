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

    public MigrationRecord(Object id, Map<String, Object> columnValues) {
        this.id = id;
        this.columnValues = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(columnValues));
    }

    public Object getId() {
        return id;
    }

    public Map<String, Object> getColumnValues() {
        return columnValues;
    }

    public Object getColumnValue(String column) {
        return columnValues.get(column);
    }
}
