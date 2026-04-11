package io.github.jasper.mybatis.encrypt.migration;

import java.util.Optional;

/**
 * Persists resumable checkpoints outside the database.
 */
public interface MigrationStateStore {

    /**
     * Load persisted state for the current plan.
     *
     * @param plan migration plan
     * @return persisted state when present
     */
    Optional<MigrationState> load(EntityMigrationPlan plan);

    /**
     * Save the latest task state.
     *
     * @param plan migration plan
     * @param state state snapshot
     */
    void save(EntityMigrationPlan plan, MigrationState state);
}
