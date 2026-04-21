package io.github.jasper.mybatis.encrypt.migration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * In-memory confirmation allowlist for integration with external configuration systems.
 *
 * <p>The legacy {@link #of(String...)} factory keeps the original one-task exact-match behavior.
 * When one policy instance must cover multiple migration tasks, use {@link #builder()} and
 * register one scope per table or per datasource/entity/table combination.</p>
 */
public class ExpectedRiskConfirmationPolicy implements MigrationConfirmationPolicy {

    private final Set<String> defaultExpectedEntries;
    private final Map<ScopeKey, Set<String>> scopedExpectedEntries;

    /**
     * Create one exact-match policy for a single migration task.
     *
     * @param expectedEntries expected mutation entries for the current task
     */
    public ExpectedRiskConfirmationPolicy(Set<String> expectedEntries) {
        this(expectedEntries, Collections.<ScopeKey, Set<String>>emptyMap());
    }

    private ExpectedRiskConfirmationPolicy(Set<String> defaultExpectedEntries,
                                           Map<ScopeKey, Set<String>> scopedExpectedEntries) {
        this.defaultExpectedEntries = immutableCopy(defaultExpectedEntries);
        this.scopedExpectedEntries = immutableScopedCopy(scopedExpectedEntries);
    }

    /**
     * Create one exact-match policy for a single migration task.
     *
     * @param expectedEntries expected mutation entries for the current task
     * @return confirmation policy
     */
    public static ExpectedRiskConfirmationPolicy of(String... expectedEntries) {
        return new ExpectedRiskConfirmationPolicy(asSet(expectedEntries));
    }

    /**
     * Create one builder for multi-scope confirmation configuration.
     *
     * @return builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Confirm the current mutation scope against the configured allowlist.
     *
     * @param plan migration plan
     * @param manifest concrete mutation manifest
     */
    @Override
    public void confirm(EntityMigrationPlan plan, MigrationRiskManifest manifest) {
        Set<String> actualEntries = toEntryTokens(manifest);
        Set<String> expectedEntries = resolveExpectedEntries(plan, manifest);
        if (expectedEntries == null) {
            throw new MigrationConfirmationException(MigrationErrorCode.CONFIRMATION_SCOPE_MISMATCH,
                    "No configured confirmation scope matched actual mutation scope for entity "
                            + plan.getEntityName() + " table " + plan.getTableName());
        }
        if (!actualEntries.equals(expectedEntries)) {
            throw new MigrationConfirmationException(MigrationErrorCode.CONFIRMATION_SCOPE_MISMATCH,
                    "Configured confirmation scope does not match actual mutation scope for entity "
                            + plan.getEntityName() + " table " + plan.getTableName());
        }
    }

    private Set<String> resolveExpectedEntries(EntityMigrationPlan plan, MigrationRiskManifest manifest) {
        if (scopedExpectedEntries.isEmpty()) {
            return defaultExpectedEntries;
        }
        ScopeKey key = ScopeKey.forDataSourceEntityTable(plan.getDataSourceName(), plan.getEntityName(), plan.getTableName());
        Set<String> scopedEntries = scopedExpectedEntries.get(key);
        if (scopedEntries != null) {
            return scopedEntries;
        }
        scopedEntries = scopedExpectedEntries.get(ScopeKey.forEntityTable(plan.getEntityName(), plan.getTableName()));
        if (scopedEntries != null) {
            return scopedEntries;
        }
        scopedEntries = scopedExpectedEntries.get(ScopeKey.forDataSourceTable(plan.getDataSourceName(), manifest.getTableName()));
        if (scopedEntries != null) {
            return scopedEntries;
        }
        return scopedExpectedEntries.get(ScopeKey.forTable(manifest.getTableName()));
    }

    private Set<String> toEntryTokens(MigrationRiskManifest manifest) {
        Set<String> actualEntries = new LinkedHashSet<>();
        for (MigrationRiskEntry entry : manifest.getEntries()) {
            actualEntries.add(entry.asToken());
        }
        return actualEntries;
    }

    private static Map<ScopeKey, Set<String>> immutableScopedCopy(Map<ScopeKey, Set<String>> scopedExpectedEntries) {
        LinkedHashMap<ScopeKey, Set<String>> copy = new LinkedHashMap<ScopeKey, Set<String>>();
        for (Map.Entry<ScopeKey, Set<String>> entry : scopedExpectedEntries.entrySet()) {
            copy.put(entry.getKey(), immutableCopy(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Set<String> immutableCopy(Set<String> expectedEntries) {
        return Collections.unmodifiableSet(new LinkedHashSet<String>(expectedEntries));
    }

    private static Set<String> asSet(String... expectedEntries) {
        LinkedHashSet<String> values = new LinkedHashSet<String>();
        if (expectedEntries == null) {
            return values;
        }
        for (String expectedEntry : expectedEntries) {
            if (expectedEntry != null && !expectedEntry.trim().isEmpty()) {
                values.add(expectedEntry.trim());
            }
        }
        return values;
    }

    /**
     * Builder for one multi-scope expected-risk confirmation policy.
     */
    public static final class Builder {

        private final Map<ScopeKey, Set<String>> scopedExpectedEntries =
                new LinkedHashMap<ScopeKey, Set<String>>();

        /**
         * Register one table-wide confirmation scope.
         *
         * @param tableName main table name
         * @param expectedEntries expected mutation entries for that table task
         * @return current builder
         */
        public Builder expectTable(String tableName, String... expectedEntries) {
            return put(ScopeKey.forTable(tableName), expectedEntries);
        }

        /**
         * Register one entity-and-table scoped confirmation definition.
         *
         * @param entityName entity name used by the migration plan
         * @param tableName main table name
         * @param expectedEntries expected mutation entries for that task
         * @return current builder
         */
        public Builder expectEntityTable(String entityName, String tableName, String... expectedEntries) {
            return put(ScopeKey.forEntityTable(entityName, tableName), expectedEntries);
        }

        /**
         * Register one datasource-and-table scoped confirmation definition.
         *
         * @param dataSourceName datasource name
         * @param tableName main table name
         * @param expectedEntries expected mutation entries for that task
         * @return current builder
         */
        public Builder expectDataSourceTable(String dataSourceName, String tableName, String... expectedEntries) {
            return put(ScopeKey.forDataSourceTable(dataSourceName, tableName), expectedEntries);
        }

        /**
         * Register one datasource/entity/table scoped confirmation definition.
         *
         * @param dataSourceName datasource name
         * @param entityName entity name used by the migration plan
         * @param tableName main table name
         * @param expectedEntries expected mutation entries for that task
         * @return current builder
         */
        public Builder expectDataSourceEntityTable(String dataSourceName,
                                                   String entityName,
                                                   String tableName,
                                                   String... expectedEntries) {
            return put(ScopeKey.forDataSourceEntityTable(dataSourceName, entityName, tableName), expectedEntries);
        }

        /**
         * Build the immutable confirmation policy.
         *
         * @return confirmation policy
         */
        public ExpectedRiskConfirmationPolicy build() {
            return new ExpectedRiskConfirmationPolicy(Collections.<String>emptySet(), scopedExpectedEntries);
        }

        private Builder put(ScopeKey key, String... expectedEntries) {
            scopedExpectedEntries.put(key, asSet(expectedEntries));
            return this;
        }
    }

    private static final class ScopeKey {

        private final String dataSourceName;
        private final String entityName;
        private final String tableName;

        private ScopeKey(String dataSourceName, String entityName, String tableName) {
            this.dataSourceName = normalize(dataSourceName);
            this.entityName = normalize(entityName);
            this.tableName = normalize(tableName);
        }

        private static ScopeKey forTable(String tableName) {
            return new ScopeKey(null, null, tableName);
        }

        private static ScopeKey forEntityTable(String entityName, String tableName) {
            return new ScopeKey(null, entityName, tableName);
        }

        private static ScopeKey forDataSourceTable(String dataSourceName, String tableName) {
            return new ScopeKey(dataSourceName, null, tableName);
        }

        private static ScopeKey forDataSourceEntityTable(String dataSourceName, String entityName, String tableName) {
            return new ScopeKey(dataSourceName, entityName, tableName);
        }

        private String normalize(String value) {
            return value == null ? null : value.trim();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ScopeKey)) {
                return false;
            }
            ScopeKey that = (ScopeKey) other;
            return Objects.equals(dataSourceName, that.dataSourceName)
                    && Objects.equals(entityName, that.entityName)
                    && Objects.equals(tableName, that.tableName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(dataSourceName, entityName, tableName);
        }
    }
}
