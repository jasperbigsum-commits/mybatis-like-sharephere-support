package io.github.jasper.mybatis.encrypt.migration;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Verifies migrated rows after write and before commit.
 */
public interface MigrationRecordVerifier {

    /**
     * Verify a migrated row inside the same transaction.
     *
     * @param connection open JDBC connection
     * @param plan migration plan
     * @param record source record
     * @throws SQLException when JDBC read fails
     */
    void verify(Connection connection, EntityMigrationPlan plan, MigrationRecord record) throws SQLException;
}
