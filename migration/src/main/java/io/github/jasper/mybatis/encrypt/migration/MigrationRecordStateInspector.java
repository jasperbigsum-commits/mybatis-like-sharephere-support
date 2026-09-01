package io.github.jasper.mybatis.encrypt.migration;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Checks whether one source record still requires migration without mutating the database.
 */
public interface MigrationRecordStateInspector {

    /**
     * Return whether the current record still requires migration.
     *
     * @param connection open JDBC connection
     * @param plan migration plan
     * @param record current source record
     * @return {@code true} when migration is still required
     * @throws SQLException when JDBC inspection fails
     */
    boolean requiresMigration(Connection connection, EntityMigrationPlan plan, MigrationRecord record) throws SQLException;
}
