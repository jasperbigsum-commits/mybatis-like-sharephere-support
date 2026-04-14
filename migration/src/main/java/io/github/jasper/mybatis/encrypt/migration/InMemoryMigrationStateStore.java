package io.github.jasper.mybatis.encrypt.migration;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Lightweight in-memory checkpoint store for application-managed migration tasks.
 */
public class InMemoryMigrationStateStore implements MigrationStateStore {

    private final ConcurrentMap<String, MigrationState> states = new ConcurrentHashMap<String, MigrationState>();

    @Override
    public Optional<MigrationState> load(EntityMigrationPlan plan) {
        MigrationState state = states.get(keyOf(plan));
        return state == null ? Optional.<MigrationState>empty() : Optional.of(copyOf(state));
    }

    @Override
    public void save(EntityMigrationPlan plan, MigrationState state) {
        states.put(keyOf(plan), copyOf(state));
    }

    private String keyOf(EntityMigrationPlan plan) {
        return plan.getEntityName() + "::" + plan.getTableName();
    }

    private MigrationState copyOf(MigrationState source) {
        MigrationState target = new MigrationState();
        target.setEntityName(source.getEntityName());
        target.setTableName(source.getTableName());
        target.setCursorColumns(source.getCursorColumns());
        target.setCursorJavaTypes(source.getCursorJavaTypes());
        target.setStatus(source.getStatus());
        target.setTotalRows(source.getTotalRows());
        target.setRangeStartValues(source.getRangeStartValues());
        target.setRangeEndValues(source.getRangeEndValues());
        target.setLastProcessedCursorValues(source.getLastProcessedCursorValues());
        target.setScannedRows(source.getScannedRows());
        target.setMigratedRows(source.getMigratedRows());
        target.setSkippedRows(source.getSkippedRows());
        target.setVerifiedRows(source.getVerifiedRows());
        target.setVerificationEnabled(source.isVerificationEnabled());
        target.setLastError(source.getLastError());
        return target;
    }
}
