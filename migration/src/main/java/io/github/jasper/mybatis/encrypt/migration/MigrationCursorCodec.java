package io.github.jasper.mybatis.encrypt.migration;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Helpers for cursor checkpoint serialization and scalar decoding.
 */
final class MigrationCursorCodec {

    private MigrationCursorCodec() {
    }

    static String display(MigrationCursor cursor) {
        if (cursor == null) {
            return null;
        }
        return cursor.isSingleColumn() ? stringifyValue(cursor.getPrimaryValue()) : cursor.toString();
    }

    static List<String> stringify(MigrationCursor cursor) {
        List<String> values = new ArrayList<String>();
        if (cursor == null) {
            return values;
        }
        for (Object value : cursor.getValues().values()) {
            values.add(stringifyValue(value));
        }
        return values;
    }

    static MigrationCursor decode(List<String> columns, List<String> rawValues, List<String> typeNames) {
        if (columns == null || columns.isEmpty() || rawValues == null || rawValues.isEmpty()) {
            return null;
        }
        if (columns.size() != rawValues.size()) {
            throw new MigrationException("Cursor checkpoint shape does not match cursor columns: " + columns);
        }
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        for (int index = 0; index < columns.size(); index++) {
            String typeName = typeNames != null && index < typeNames.size() ? typeNames.get(index) : null;
            values.put(columns.get(index), decodeScalar(rawValues.get(index), typeName));
        }
        return new MigrationCursor(values);
    }

    static String stringifyValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    static Object decodeScalar(String value, String typeName) {
        if (value == null || typeName == null) {
            return value;
        }
        if (Long.class.getName().equals(typeName) || "long".equals(typeName)) {
            return Long.valueOf(value);
        }
        if (Integer.class.getName().equals(typeName) || "int".equals(typeName)) {
            return Integer.valueOf(value);
        }
        if (Short.class.getName().equals(typeName) || "short".equals(typeName)) {
            return Short.valueOf(value);
        }
        if (Double.class.getName().equals(typeName) || "double".equals(typeName)) {
            return Double.valueOf(value);
        }
        if (Float.class.getName().equals(typeName) || "float".equals(typeName)) {
            return Float.valueOf(value);
        }
        if (BigInteger.class.getName().equals(typeName)) {
            return new BigInteger(value);
        }
        if (BigDecimal.class.getName().equals(typeName)) {
            return new BigDecimal(value);
        }
        return value;
    }
}
