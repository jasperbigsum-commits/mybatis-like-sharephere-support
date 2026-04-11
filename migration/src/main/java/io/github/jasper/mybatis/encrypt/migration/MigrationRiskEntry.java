package io.github.jasper.mybatis.encrypt.migration;

import java.util.Objects;

/**
 * One concrete table/column mutation that operators must confirm before execution.
 */
public final class MigrationRiskEntry {

    private final String operation;
    private final String tableName;
    private final String columnName;

    public MigrationRiskEntry(String operation, String tableName, String columnName) {
        this.operation = operation;
        this.tableName = tableName;
        this.columnName = columnName;
    }

    public String getOperation() {
        return operation;
    }

    public String getTableName() {
        return tableName;
    }

    public String getColumnName() {
        return columnName;
    }

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
