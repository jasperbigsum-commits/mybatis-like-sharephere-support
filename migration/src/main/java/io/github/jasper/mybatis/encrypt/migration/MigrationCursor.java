package io.github.jasper.mybatis.encrypt.migration;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Ordered cursor snapshot for one source row or one range boundary.
 */
public final class MigrationCursor {

    private final Map<String, Object> values;

    /**
     * Create one immutable cursor snapshot.
     *
     * @param values ordered cursor-column values
     */
    public MigrationCursor(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("cursor values must not be empty");
        }
        this.values = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(values));
    }

    /**
     * Return ordered cursor-column values.
     *
     * @return immutable ordered cursor values
     */
    public Map<String, Object> getValues() {
        return values;
    }

    /**
     * Return one cursor-column value.
     *
     * @param column cursor column
     * @return cursor value, or {@code null}
     */
    public Object getValue(String column) {
        return values.get(column);
    }

    /**
     * Return whether this cursor uses one column only.
     *
     * @return {@code true} when the cursor contains one column
     */
    public boolean isSingleColumn() {
        return values.size() == 1;
    }

    /**
     * Return the first cursor value in column order.
     *
     * @return first cursor value
     */
    public Object getPrimaryValue() {
        return values.values().iterator().next();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MigrationCursor)) {
            return false;
        }
        MigrationCursor that = (MigrationCursor) other;
        return values.equals(that.values);
    }

    @Override
    public int hashCode() {
        return Objects.hash(values);
    }

    @Override
    public String toString() {
        return values.toString();
    }
}
