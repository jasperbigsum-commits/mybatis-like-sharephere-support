package io.github.jasper.mybatis.encrypt.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * Reads source rows in resumable batches.
 */
public interface MigrationRecordReader {

    /**
     * Read the next batch ordered by the configured cursor columns.
     *
     * @param connection open JDBC connection
     * @param plan migration plan
     * @param lastProcessedCursor inclusive checkpoint boundary on the cursor, null means from start
     * @return next batch
     * @throws SQLException when JDBC read fails
     */
    List<MigrationRecord> readBatch(Connection connection, EntityMigrationPlan plan, MigrationCursor lastProcessedCursor)
            throws SQLException;
}
