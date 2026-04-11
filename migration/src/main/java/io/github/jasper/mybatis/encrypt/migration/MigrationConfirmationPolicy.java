package io.github.jasper.mybatis.encrypt.migration;

/**
 * Confirms operator awareness of tables and columns that will be mutated.
 */
public interface MigrationConfirmationPolicy {

    /**
     * Validate or bootstrap operator confirmation before execution starts.
     *
     * @param plan migration plan
     * @param manifest concrete mutation manifest
     */
    void confirm(EntityMigrationPlan plan, MigrationRiskManifest manifest);
}
