package io.github.jasper.mybatis.encrypt.migration;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * Orchestrates range scan, resumable batching, write, verification and state persistence.
 */
public class JdbcEntityMigrationTask implements MigrationTask {

    private final DataSource dataSource;
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
        this.dataSource = dataSource;
        this.plan = plan;
        this.rangeReader = rangeReader;
        this.recordReader = recordReader;
        this.recordWriter = recordWriter;
        this.recordVerifier = recordVerifier;
        this.stateStore = stateStore;
        this.confirmationPolicy = confirmationPolicy;
        this.riskManifestFactory = new MigrationRiskManifestFactory();
    }

    @Override
    public MigrationReport execute() {
        confirmationPolicy.confirm(plan, riskManifestFactory.create(plan));
        MigrationState state = stateStore.load(plan).orElseGet(this::newState);
        refreshRange(state);
        if (state.getTotalRows() == 0L) {
            state.setStatus(MigrationStatus.COMPLETED);
            state.setLastError(null);
            stateStore.save(plan, state);
            return state.toReport();
        }
        if (state.getStatus() == MigrationStatus.COMPLETED && isCompletedForCurrentRange(state)) {
            return state.toReport();
        }
        state.setStatus(MigrationStatus.RUNNING);
        state.setLastError(null);
        stateStore.save(plan, state);
        Object lastProcessedId = decodeId(state.getLastProcessedId(), state.getIdJavaType());
        try {
            while (true) {
                List<MigrationRecord> batch;
                try (Connection connection = dataSource.getConnection()) {
                    boolean previousAutoCommit = connection.getAutoCommit();
                    connection.setAutoCommit(false);
                    try {
                        batch = recordReader.readBatch(connection, plan, lastProcessedId);
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
                        Object batchLastProcessedId = lastProcessedId;
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
                            batchLastProcessedId = record.getId();
                        }
                        connection.commit();
                        state.setScannedRows(state.getScannedRows() + batchScanned);
                        state.setMigratedRows(state.getMigratedRows() + batchMigrated);
                        state.setSkippedRows(state.getSkippedRows() + batchSkipped);
                        state.setVerifiedRows(state.getVerifiedRows() + batchVerified);
                        lastProcessedId = batchLastProcessedId;
                        state.setLastProcessedId(stringifyId(lastProcessedId));
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
            state.setLastProcessedId(stringifyId(lastProcessedId));
            state.setLastError(ex.getMessage());
            stateStore.save(plan, state);
            if (ex instanceof RuntimeException) {
                throw (RuntimeException) ex;
            }
            throw new MigrationException("Failed to execute migration task for entity: "
                    + plan.getEntityType().getName(), ex);
        }
    }

    private MigrationState newState() {
        MigrationState state = new MigrationState();
        state.setEntityName(plan.getEntityType().getName());
        state.setTableName(plan.getTableName());
        state.setIdColumn(plan.getIdColumn());
        state.setVerificationEnabled(plan.isVerifyAfterWrite());
        return state;
    }

    private void refreshRange(MigrationState state) {
        try (Connection connection = dataSource.getConnection()) {
            MigrationRange range = rangeReader.readRange(connection, plan);
            state.setEntityName(plan.getEntityType().getName());
            state.setTableName(plan.getTableName());
            state.setIdColumn(plan.getIdColumn());
            state.setIdJavaType(range.getIdJavaType() != null ? range.getIdJavaType() : state.getIdJavaType());
            state.setVerificationEnabled(plan.isVerifyAfterWrite());
            state.setTotalRows(range.getTotalRows());
            state.setRangeStart(stringifyId(range.getRangeStart()));
            state.setRangeEnd(stringifyId(range.getRangeEnd()));
        } catch (SQLException ex) {
            throw new MigrationException("Failed to read migration range for entity: "
                    + plan.getEntityType().getName(), ex);
        }
    }

    private boolean isCompletedForCurrentRange(MigrationState state) {
        String currentEnd = state.getRangeEnd();
        return currentEnd == null ? state.getLastProcessedId() == null : currentEnd.equals(state.getLastProcessedId());
    }

    private String stringifyId(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Object decodeId(String value, String typeName) {
        if (value == null || typeName == null) {
            return value;
        }
        if (Long.class.getName().equals(typeName) || "long".equals(typeName)) {
            return Long.valueOf(value);
        }
        if (Integer.class.getName().equals(typeName) || "int".equals(typeName)) {
            return Integer.valueOf(value);
        }
        if (Short.class.getName().equals(typeName) || "short".equals(typeName)) {
            return Short.valueOf(value);
        }
        if (Double.class.getName().equals(typeName) || "double".equals(typeName)) {
            return Double.valueOf(value);
        }
        if (Float.class.getName().equals(typeName) || "float".equals(typeName)) {
            return Float.valueOf(value);
        }
        if (BigInteger.class.getName().equals(typeName)) {
            return new BigInteger(value);
        }
        if (BigDecimal.class.getName().equals(typeName)) {
            return new BigDecimal(value);
        }
        return value;
    }
}
