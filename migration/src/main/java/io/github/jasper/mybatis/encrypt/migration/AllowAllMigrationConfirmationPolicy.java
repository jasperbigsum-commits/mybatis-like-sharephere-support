package io.github.jasper.mybatis.encrypt.migration;

/**
 * Backward-compatible policy that skips operator confirmation.
 */
public final class AllowAllMigrationConfirmationPolicy implements MigrationConfirmationPolicy {

    /**
     * 允许所有迁移策略
     */
    public static final AllowAllMigrationConfirmationPolicy INSTANCE = new AllowAllMigrationConfirmationPolicy();

    /**
     * 构造参数
     */
    private AllowAllMigrationConfirmationPolicy() {
    }

    /**
     * 确认拦截逻辑
     * @param plan migration plan 迁移计划
     * @param manifest concrete mutation manifest 迁移任务元数据信息
     */
    @Override
    public void confirm(EntityMigrationPlan plan, MigrationRiskManifest manifest) {
        // no-op
    }
}
