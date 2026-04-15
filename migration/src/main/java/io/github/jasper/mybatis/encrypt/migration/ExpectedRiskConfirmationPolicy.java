package io.github.jasper.mybatis.encrypt.migration;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * In-memory allowlist for integration with external configuration systems.
 */
public class ExpectedRiskConfirmationPolicy implements MigrationConfirmationPolicy {

    private final Set<String> expectedEntries;

    /**
     * 构造方法
     * @param expectedEntries 期望实体集合
     */
    public ExpectedRiskConfirmationPolicy(Set<String> expectedEntries) {
        this.expectedEntries = new LinkedHashSet<>(expectedEntries);
    }

    /**
     * 静态方法构造
     * @param expectedEntries 期望实体集合
     * @return 任务策略
     */
    public static ExpectedRiskConfirmationPolicy of(String... expectedEntries) {
        return new ExpectedRiskConfirmationPolicy(new LinkedHashSet<>(Arrays.asList(expectedEntries)));
    }

    /**
     * 确认信息
     * @param plan migration plan
     * @param manifest concrete mutation manifest
     */
    @Override
    public void confirm(EntityMigrationPlan plan, MigrationRiskManifest manifest) {
        Set<String> actualEntries = new LinkedHashSet<>();
        for (MigrationRiskEntry entry : manifest.getEntries()) {
            actualEntries.add(entry.asToken());
        }
        if (!actualEntries.equals(expectedEntries)) {
            throw new MigrationConfirmationException(MigrationErrorCode.CONFIRMATION_SCOPE_MISMATCH,
                    "Configured confirmation scope does not match actual mutation scope for entity: "
                            + plan.getEntityName());
        }
    }
}
