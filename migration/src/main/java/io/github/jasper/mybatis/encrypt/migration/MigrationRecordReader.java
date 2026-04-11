package io.github.jasper.mybatis.encrypt.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * Reads source rows in resumable batches.
 */
public interface MigrationRecordReader {

    /**
     * Read the next batch ordered by the id column.
     *
     * @param connection open JDBC connection
     * @param plan migration plan
     * @param lastProcessedId inclusive checkpoint boundary, null means from start
     * @return next batch
     * @throws SQLException when JDBC read fails
     */
    List<MigrationRecord> readBatch(Connection connection, EntityMigrationPlan plan, Object lastProcessedId)
            throws SQLException;
}
