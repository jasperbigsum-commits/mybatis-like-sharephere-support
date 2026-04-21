package io.github.jasper.mybatis.encrypt.migration.jdbc;

import io.github.jasper.mybatis.encrypt.config.SqlDialectContextHolder;
import io.github.jasper.mybatis.encrypt.migration.EntityMigrationPlan;
import io.github.jasper.mybatis.encrypt.migration.MigrationConfirmationPolicy;
import io.github.jasper.mybatis.encrypt.migration.MigrationCursor;
import io.github.jasper.mybatis.encrypt.migration.MigrationErrorCode;
import io.github.jasper.mybatis.encrypt.migration.MigrationExecutionException;
import io.github.jasper.mybatis.encrypt.migration.MigrationCheckpointLock;
import io.github.jasper.mybatis.encrypt.migration.MigrationRange;
import io.github.jasper.mybatis.encrypt.migration.MigrationRangeReader;
import io.github.jasper.mybatis.encrypt.migration.MigrationRecord;
import io.github.jasper.mybatis.encrypt.migration.MigrationRecordReader;
import io.github.jasper.mybatis.encrypt.migration.MigrationRecordStateInspector;
import io.github.jasper.mybatis.encrypt.migration.MigrationRecordVerifier;
import io.github.jasper.mybatis.encrypt.migration.MigrationRecordWriter;
import io.github.jasper.mybatis.encrypt.migration.MigrationReport;
import io.github.jasper.mybatis.encrypt.migration.MigrationState;
import io.github.jasper.mybatis.encrypt.migration.MigrationStateStore;
import io.github.jasper.mybatis.encrypt.migration.MigrationStatus;
import io.github.jasper.mybatis.encrypt.migration.MigrationTask;
import io.github.jasper.mybatis.encrypt.migration.risk.MigrationRiskManifestFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Orchestrates range scan, resumable batching, write, verification and state persistence.
 */
public class JdbcEntityMigrationTask implements MigrationTask {

    private final DataSource dataSource;
    private final String dataSourceName;
    private final EntityMigrationPlan plan;
    private final MigrationRangeReader rangeReader;
    private final MigrationRecordReader recordReader;
    private final MigrationRecordWriter recordWriter;
    private final MigrationRecordVerifier recordVerifier;
    private final MigrationStateStore stateStore;
    private final MigrationConfirmationPolicy confirmationPolicy;
    private final MigrationRiskManifestFactory riskManifestFactory;

    /**
     * jdbc实例迁移任务
     * @param dataSource 数据源
     * @param dataSourceName 数据源名称
     * @param plan 计划
     * @param rangeReader 读取范围限制器
     * @param recordReader 读取器
     * @param recordWriter 写入器
     * @param recordVerifier 验证器
     * @param stateStore 状态存储器
     * @param confirmationPolicy 确认策略
     */
    public JdbcEntityMigrationTask(DataSource dataSource,
                                   String dataSourceName,
                                   EntityMigrationPlan plan,
                                   MigrationRangeReader rangeReader,
                                   MigrationRecordReader recordReader,
                                   MigrationRecordWriter recordWriter,
                                   MigrationRecordVerifier recordVerifier,
                                   MigrationStateStore stateStore,
                                   MigrationConfirmationPolicy confirmationPolicy) {
        this.dataSource = dataSource;
        this.dataSourceName = dataSourceName;
        this.plan = plan;
        this.rangeReader = rangeReader;
        this.recordReader = recordReader;
        this.recordWriter = recordWriter;
        this.recordVerifier = recordVerifier;
        this.stateStore = stateStore;
        this.confirmationPolicy = confirmationPolicy;
        this.riskManifestFactory = new MigrationRiskManifestFactory();
    }

    /**
     * jdbc实例迁移任务
     * @param dataSource 数据源
     * @param plan 计划
     * @param rangeReader 读取范围限制器
     * @param recordReader 读取器
     * @param recordWriter 写入器
     * @param recordVerifier 验证器
     * @param stateStore 状态存储器
     * @param confirmationPolicy 确认策略
     */
    public JdbcEntityMigrationTask(DataSource dataSource,
                                   EntityMigrationPlan plan,
                                   MigrationRangeReader rangeReader,
                                   MigrationRecordReader recordReader,
                                   MigrationRecordWriter recordWriter,
                                   MigrationRecordVerifier recordVerifier,
                                   MigrationStateStore stateStore,
                                   MigrationConfirmationPolicy confirmationPolicy) {
        this(dataSource, plan == null ? null : plan.getDataSourceName(), plan, rangeReader, recordReader, recordWriter,
                recordVerifier, stateStore, confirmationPolicy);
    }

    @Override
    public MigrationReport execute() {
        try (SqlDialectContextHolder.Scope ignored = SqlDialectContextHolder.open(dataSourceName);
             MigrationCheckpointLock checkpointLock = stateStore.acquireCheckpointLock(plan)) {
            confirmationPolicy.confirm(plan, riskManifestFactory.create(plan));
            MigrationState state = stateStore.load(plan).orElse(null);
            ExecutionSnapshot executionSnapshot = readExecutionSnapshot();
            if (state == null) {
                state = newState();
            } else {
                assertStateCompatible(state, executionSnapshot);
            }
            applyExecutionSnapshot(state, executionSnapshot);
            if (state.getTotalRows() == 0L) {
                state.setStatus(MigrationStatus.COMPLETED);
                state.setLastError(null);
                stateStore.save(plan, state);
                return state.toReport();
            }
            boolean completedForCurrentRange = state.getStatus() == MigrationStatus.COMPLETED
                    && isCompletedForCurrentRange(state);
            if (completedForCurrentRange && isCompletedStateStillValid()) {
                return state.toReport();
            }
            if (completedForCurrentRange) {
                resetProgress(state);
            }
            state.setStatus(MigrationStatus.RUNNING);
            state.setLastError(null);
            stateStore.save(plan, state);
            MigrationCursor lastProcessedCursor = MigrationCursorCodec.decode(
                    plan.getCursorColumns(), state.getLastProcessedCursorValues(), state.getCursorJavaTypes());
            try {
                while (true) {
                    List<MigrationRecord> batch;
                    try (Connection connection = dataSource.getConnection()) {
                        boolean previousAutoCommit = connection.getAutoCommit();
                        connection.setAutoCommit(false);
                        try {
                            batch = recordReader.readBatch(connection, plan, lastProcessedCursor);
                            if (batch.isEmpty()) {
                                connection.commit();
                                state.setStatus(MigrationStatus.COMPLETED);
                                state.setLastError(null);
                                stateStore.save(plan, state);
                                return state.toReport();
                            }
                            long batchScanned = 0L;
                            long batchMigrated = 0L;
                            long batchSkipped = 0L;
                            long batchVerified = 0L;
                            MigrationCursor batchLastProcessedCursor = lastProcessedCursor;
                            for (MigrationRecord record : batch) {
                                batchScanned++;
                                boolean changed = recordWriter.write(connection, plan, record);
                                if (changed) {
                                    batchMigrated++;
                                    if (plan.isVerifyAfterWrite()) {
                                        recordVerifier.verify(connection, plan, record);
                                        batchVerified++;
                                    }
                                } else {
                                    batchSkipped++;
                                }
                                batchLastProcessedCursor = record.getCursor();
                            }
                            connection.commit();
                            state.setScannedRows(state.getScannedRows() + batchScanned);
                            state.setMigratedRows(state.getMigratedRows() + batchMigrated);
                            state.setSkippedRows(state.getSkippedRows() + batchSkipped);
                            state.setVerifiedRows(state.getVerifiedRows() + batchVerified);
                            lastProcessedCursor = batchLastProcessedCursor;
                            state.setLastProcessedCursorValues(MigrationCursorCodec.stringify(lastProcessedCursor));
                            state.setLastError(null);
                            stateStore.save(plan, state);
                        } catch (RuntimeException | SQLException ex) {
                            connection.rollback();
                            throw ex;
                        } finally {
                            connection.setAutoCommit(previousAutoCommit);
                        }
                    }
                }
            } catch (RuntimeException | SQLException ex) {
                state.setStatus(MigrationStatus.FAILED);
                state.setLastProcessedCursorValues(MigrationCursorCodec.stringify(lastProcessedCursor));
                state.setLastError(ex.getMessage());
                stateStore.save(plan, state);
                if (ex instanceof RuntimeException) {
                    throw (RuntimeException) ex;
                }
                throw new MigrationExecutionException(MigrationErrorCode.EXECUTION_FAILED,
                        "Failed to execute migration task for entity: " + plan.getEntityName(), ex);
            }
        }
    }

    private MigrationState newState() {
        MigrationState state = new MigrationState();
        state.setDataSourceName(plan.getDataSourceName());
        state.setPlanSignature(planSignature());
        state.setEntityName(plan.getEntityName());
        state.setTableName(plan.getTableName());
        state.setCursorColumns(plan.getCursorColumns());
        state.setVerificationEnabled(plan.isVerifyAfterWrite());
        return state;
    }

    private ExecutionSnapshot readExecutionSnapshot() {
        try (Connection connection = dataSource.getConnection()) {
            MigrationRange range = rangeReader.readRange(connection, plan);
            return new ExecutionSnapshot(range, dataSourceFingerprint(connection), planSignature());
        } catch (SQLException ex) {
            throw new MigrationExecutionException(MigrationErrorCode.RANGE_READ_FAILED,
                    "Failed to read migration range for entity: " + plan.getEntityName(), ex);
        }
    }

    private void applyExecutionSnapshot(MigrationState state, ExecutionSnapshot executionSnapshot) {
        state.setDataSourceName(plan.getDataSourceName());
        state.setDataSourceFingerprint(executionSnapshot.dataSourceFingerprint());
        state.setPlanSignature(executionSnapshot.planSignature());
        state.setEntityName(plan.getEntityName());
        state.setTableName(plan.getTableName());
        state.setCursorColumns(plan.getCursorColumns());
        state.setCursorJavaTypes(!executionSnapshot.range().getCursorJavaTypes().isEmpty()
                ? executionSnapshot.range().getCursorJavaTypes() : state.getCursorJavaTypes());
        state.setVerificationEnabled(plan.isVerifyAfterWrite());
        state.setTotalRows(executionSnapshot.range().getTotalRows());
        state.setRangeStartValues(MigrationCursorCodec.stringify(executionSnapshot.range().getRangeStartCursor()));
        state.setRangeEndValues(MigrationCursorCodec.stringify(executionSnapshot.range().getRangeEndCursor()));
    }

    private void assertStateCompatible(MigrationState state, ExecutionSnapshot executionSnapshot) {
        if (Objects.equals(state.getPlanSignature(), executionSnapshot.planSignature())
                && Objects.equals(state.getDataSourceFingerprint(), executionSnapshot.dataSourceFingerprint())) {
            return;
        }
        throw new MigrationExecutionException(MigrationErrorCode.STATE_INCOMPATIBLE,
                "Migration checkpoint is incompatible with the current task. "
                        + "The existing state was not overwritten. "
                        + "Keep the state file to resume the original task, or move/delete it only when restarting "
                        + "from scratch is intentional. "
                        + "entity=" + plan.getEntityName()
                        + ", table=" + plan.getTableName()
                        + ", storedPlanSignature=" + state.getPlanSignature()
                        + ", currentPlanSignature=" + executionSnapshot.planSignature()
                        + ", storedDataSourceFingerprint=" + state.getDataSourceFingerprint()
                        + ", currentDataSourceFingerprint=" + executionSnapshot.dataSourceFingerprint(),
                null);
    }

    private String dataSourceFingerprint(Connection connection) throws SQLException {
        if (connection == null) {
            return null;
        }
        DatabaseMetaData metaData = connection.getMetaData();
        if (metaData == null) {
            return null;
        }
        return safe(metaData.getDatabaseProductName())
                + "|" + safe(metaData.getURL())
                + "|" + safe(metaData.getUserName());
    }

    private String planSignature() {
        StringBuilder builder = new StringBuilder();
        builder.append(plan.getEntityName()).append('|')
                .append(plan.getTableName()).append('|')
                .append(plan.getCursorColumns()).append('|')
                .append(plan.isVerifyAfterWrite()).append('|');
        for (io.github.jasper.mybatis.encrypt.migration.EntityMigrationColumnPlan columnPlan : plan.getColumnPlans()) {
            builder.append(columnPlan.getProperty()).append(':')
                    .append(columnPlan.getSourceColumn()).append(':')
                    .append(columnPlan.getStorageColumn()).append(':')
                    .append(columnPlan.getAssistedQueryColumn()).append(':')
                    .append(columnPlan.getLikeQueryColumn()).append(':')
                    .append(columnPlan.getMaskedColumn()).append(':')
                    .append(columnPlan.getStorageTable()).append(':')
                    .append(columnPlan.getBackupColumn()).append(';');
        }
        return builder.toString();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private boolean isCompletedForCurrentRange(MigrationState state) {
        List<String> currentEnd = state.getRangeEndValues();
        List<String> lastProcessed = state.getLastProcessedCursorValues();
        return currentEnd == null || currentEnd.isEmpty()
                ? lastProcessed == null || lastProcessed.isEmpty()
                : currentEnd.equals(lastProcessed == null ? Collections.<String>emptyList() : lastProcessed);
    }

    private boolean isCompletedStateStillValid() {
        if (!(recordWriter instanceof MigrationRecordStateInspector)) {
            return true;
        }
        MigrationRecordStateInspector inspector = (MigrationRecordStateInspector) recordWriter;
        try (Connection connection = dataSource.getConnection()) {
            MigrationCursor cursor = null;
            while (true) {
                List<MigrationRecord> batch = recordReader.readBatch(connection, plan, cursor);
                if (batch.isEmpty()) {
                    return true;
                }
                for (MigrationRecord record : batch) {
                    if (inspector.requiresMigration(connection, plan, record)) {
                        return false;
                    }
                    cursor = record.getCursor();
                }
            }
        } catch (SQLException ex) {
            throw new MigrationExecutionException(MigrationErrorCode.EXECUTION_FAILED,
                    "Failed to validate completed migration state for entity: " + plan.getEntityName(), ex);
        }
    }

    private void resetProgress(MigrationState state) {
        state.setLastProcessedCursorValues(Collections.<String>emptyList());
        state.setScannedRows(0L);
        state.setMigratedRows(0L);
        state.setSkippedRows(0L);
        state.setVerifiedRows(0L);
    }

    private static final class ExecutionSnapshot {

        private final MigrationRange range;
        private final String dataSourceFingerprint;
        private final String planSignature;

        private ExecutionSnapshot(MigrationRange range, String dataSourceFingerprint, String planSignature) {
            this.range = range;
            this.dataSourceFingerprint = dataSourceFingerprint;
            this.planSignature = planSignature;
        }

        private MigrationRange range() {
            return range;
        }

        private String dataSourceFingerprint() {
            return dataSourceFingerprint;
        }

        private String planSignature() {
            return planSignature;
        }
    }
}
