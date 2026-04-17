package io.github.jasper.mybatis.encrypt.migration.jdbc;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;

/**
 * Binds migration JDBC parameters with stable type-aware mappings instead of relying on driver-side inference.
 */
final class MigrationJdbcParameterBinder {

    private MigrationJdbcParameterBinder() {
    }

    static void bind(PreparedStatement statement, int parameterIndex, Object value) throws SQLException {
        if (value == null) {
            statement.setObject(parameterIndex, null);
            return;
        }
        if (value instanceof String) {
            statement.setString(parameterIndex, (String) value);
            return;
        }
        if (value instanceof Integer) {
            statement.setInt(parameterIndex, (Integer) value);
            return;
        }
        if (value instanceof Long) {
            statement.setLong(parameterIndex, (Long) value);
            return;
        }
        if (value instanceof Short) {
            statement.setShort(parameterIndex, (Short) value);
            return;
        }
        if (value instanceof Byte) {
            statement.setByte(parameterIndex, (Byte) value);
            return;
        }
        if (value instanceof Double) {
            statement.setDouble(parameterIndex, (Double) value);
            return;
        }
        if (value instanceof Float) {
            statement.setFloat(parameterIndex, (Float) value);
            return;
        }
        if (value instanceof Boolean) {
            statement.setBoolean(parameterIndex, (Boolean) value);
            return;
        }
        if (value instanceof BigDecimal) {
            statement.setBigDecimal(parameterIndex, (BigDecimal) value);
            return;
        }
        if (value instanceof BigInteger) {
            statement.setBigDecimal(parameterIndex, new BigDecimal((BigInteger) value));
            return;
        }
        if (value instanceof java.sql.Date) {
            statement.setDate(parameterIndex, (java.sql.Date) value);
            return;
        }
        if (value instanceof Timestamp) {
            statement.setTimestamp(parameterIndex, (Timestamp) value);
            return;
        }
        if (value instanceof Time) {
            statement.setTime(parameterIndex, (Time) value);
            return;
        }
        if (value instanceof java.util.Date) {
            statement.setTimestamp(parameterIndex, new Timestamp(((java.util.Date) value).getTime()));
            return;
        }
        if (value instanceof byte[]) {
            statement.setBytes(parameterIndex, (byte[]) value);
            return;
        }
        statement.setObject(parameterIndex, value);
    }
}
