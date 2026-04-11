package io.github.jasper.mybatis.encrypt.migration;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * In-memory allowlist for integration with external configuration systems.
 */
public class ExpectedRiskConfirmationPolicy implements MigrationConfirmationPolicy {

    private final Set<String> expectedEntries;

    public ExpectedRiskConfirmationPolicy(Set<String> expectedEntries) {
        this.expectedEntries = new LinkedHashSet<String>(expectedEntries);
    }

    public static ExpectedRiskConfirmationPolicy of(String... expectedEntries) {
        return new ExpectedRiskConfirmationPolicy(new LinkedHashSet<String>(Arrays.asList(expectedEntries)));
    }

    @Override
    public void confirm(EntityMigrationPlan plan, MigrationRiskManifest manifest) {
        Set<String> actualEntries = new LinkedHashSet<String>();
        for (MigrationRiskEntry entry : manifest.getEntries()) {
            actualEntries.add(entry.asToken());
        }
        if (!actualEntries.equals(expectedEntries)) {
            throw new MigrationException("Configured confirmation scope does not match actual mutation scope for entity: "
                    + plan.getEntityType().getName());
        }
    }
}
