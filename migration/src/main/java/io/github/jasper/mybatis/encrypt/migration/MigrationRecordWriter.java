package io.github.jasper.mybatis.encrypt.migration;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Writes encrypted values into same-table or separate-table storage.
 */
public interface MigrationRecordWriter {

    /**
     * Apply migration mutations for a single row.
     *
     * @param connection open JDBC connection
     * @param plan migration plan
     * @param record source record
     * @return true if the row changed
     * @throws SQLException when JDBC write fails
     */
    boolean write(Connection connection, EntityMigrationPlan plan, MigrationRecord record) throws SQLException;
}
