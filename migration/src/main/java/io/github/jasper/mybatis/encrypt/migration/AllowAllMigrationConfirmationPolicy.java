package io.github.jasper.mybatis.encrypt.migration;

/**
 * Backward-compatible policy that skips operator confirmation.
 */
public final class AllowAllMigrationConfirmationPolicy implements MigrationConfirmationPolicy {

    public static final AllowAllMigrationConfirmationPolicy INSTANCE = new AllowAllMigrationConfirmationPolicy();

    private AllowAllMigrationConfirmationPolicy() {
    }

    @Override
    public void confirm(EntityMigrationPlan plan, MigrationRiskManifest manifest) {
        // no-op
    }
}
