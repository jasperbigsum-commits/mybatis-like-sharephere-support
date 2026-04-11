package io.github.jasper.mybatis.encrypt.migration;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Reads table range metadata for checkpoints and progress files.
 */
public interface MigrationRangeReader {

    /**
     * Read total rows and id range for the current task.
     *
     * @param connection open JDBC connection
     * @param plan migration plan
     * @return table range metadata
     * @throws SQLException when JDBC read fails
     */
    MigrationRange readRange(Connection connection, EntityMigrationPlan plan) throws SQLException;
}
