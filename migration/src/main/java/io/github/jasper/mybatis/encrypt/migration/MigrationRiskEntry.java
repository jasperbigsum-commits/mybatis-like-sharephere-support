package io.github.jasper.mybatis.encrypt.migration;

import java.util.Objects;

/**
 * One concrete table/column mutation that operators must confirm before execution.
 */
public final class MigrationRiskEntry {

    private final String operation;
    private final String tableName;
    private final String columnName;

    /**
     * Create one immutable risk entry.
     *
     * @param operation mutation operation such as INSERT or UPDATE
     * @param tableName affected table name
     * @param columnName affected column name
     */
    public MigrationRiskEntry(String operation, String tableName, String columnName) {
        this.operation = operation;
        this.tableName = tableName;
        this.columnName = columnName;
    }

    /**
     * Return the mutation operation name.
     *
     * @return operation name
     */
    public String getOperation() {
        return operation;
    }

    /**
     * Return the affected table name.
     *
     * @return table name
     */
    public String getTableName() {
        return tableName;
    }

    /**
     * Return the affected column name.
     *
     * @return column name
     */
    public String getColumnName() {
        return columnName;
    }

    /**
     * Convert the entry to a stable token for text-based confirmation files.
     *
     * @return stable operation token
     */
    public String asToken() {
        return operation + "|" + tableName + "|" + columnName;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MigrationRiskEntry)) {
            return false;
        }
        MigrationRiskEntry that = (MigrationRiskEntry) other;
        return Objects.equals(operation, that.operation)
                && Objects.equals(tableName, that.tableName)
                && Objects.equals(columnName, that.columnName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(operation, tableName, columnName);
    }
}
