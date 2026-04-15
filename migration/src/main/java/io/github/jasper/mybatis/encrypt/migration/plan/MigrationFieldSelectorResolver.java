package io.github.jasper.mybatis.encrypt.migration.plan;

import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import io.github.jasper.mybatis.encrypt.migration.EntityMigrationDefinition;
import io.github.jasper.mybatis.encrypt.migration.MigrationFieldSelectorException;
import io.github.jasper.mybatis.encrypt.util.NameUtils;
import io.github.jasper.mybatis.encrypt.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Resolves user-facing migration selectors to registered encrypt field rules.
 *
 * <p>The definition layer can now use either the encrypt property name or the plaintext source
 * column name. This resolver keeps that mapping logic in one place and fails fast when a selector
 * never matches any registered field.</p>
 */
final class MigrationFieldSelectorResolver {

    private final Set<String> includedSelectors;
    private final Set<String> unresolvedIncludedSelectors;
    private final Map<String, String> backupColumns;
    private final Set<String> unresolvedBackupSelectors;
    private final Map<String, String> rawSelectorsByKey;

    MigrationFieldSelectorResolver(EntityMigrationDefinition definition) {
        this.rawSelectorsByKey = new LinkedHashMap<>();
        this.includedSelectors = normalizeSelectors(definition.getIncludedFields());
        this.unresolvedIncludedSelectors = new LinkedHashSet<>(includedSelectors);
        this.backupColumns = normalizeBackupColumns(definition.getBackupColumns());
        this.unresolvedBackupSelectors = new LinkedHashSet<>(backupColumns.keySet());
    }

    boolean includes(EncryptColumnRule columnRule) {
        if (includedSelectors.isEmpty()) {
            return true;
        }
        boolean matched = matchesSelector(columnRule, includedSelectors);
        if (matched) {
            markResolved(columnRule, unresolvedIncludedSelectors);
        }
        return matched;
    }

    String resolveBackupColumn(EncryptColumnRule columnRule) {
        String propertySelector = normalizeSelector(columnRule.property());
        if (backupColumns.containsKey(propertySelector)) {
            unresolvedBackupSelectors.remove(propertySelector);
            return backupColumns.get(propertySelector);
        }
        String columnSelector = normalizeSelector(columnRule.column());
        if (backupColumns.containsKey(columnSelector)) {
            unresolvedBackupSelectors.remove(columnSelector);
            return backupColumns.get(columnSelector);
        }
        return null;
    }

    void assertResolved() {
        if (!unresolvedIncludedSelectors.isEmpty()) {
            throw new MigrationFieldSelectorException("Unknown migration field selector(s): "
                    + describeSelectors(unresolvedIncludedSelectors));
        }
        if (!unresolvedBackupSelectors.isEmpty()) {
            throw new MigrationFieldSelectorException("Backup column selector(s) did not match any encrypt field: "
                    + describeSelectors(unresolvedBackupSelectors));
        }
    }

    private Set<String> normalizeSelectors(Set<String> selectors) {
        Set<String> normalizedSelectors = new LinkedHashSet<>();
        for (String selector : selectors) {
            String normalized = normalizeSelector(selector);
            if (normalized == null) {
                continue;
            }
            normalizedSelectors.add(normalized);
            rawSelectorsByKey.putIfAbsent(normalized, selector);
        }
        return normalizedSelectors;
    }

    private Map<String, String> normalizeBackupColumns(Map<String, String> configuredBackupColumns) {
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : configuredBackupColumns.entrySet()) {
            String normalizedSelector = normalizeSelector(entry.getKey());
            if (normalizedSelector == null) {
                continue;
            }
            normalized.put(normalizedSelector, entry.getValue());
            rawSelectorsByKey.putIfAbsent(normalizedSelector, entry.getKey());
        }
        return normalized;
    }

    private boolean matchesSelector(EncryptColumnRule columnRule, Set<String> selectors) {
        return selectors.contains(normalizeSelector(columnRule.property()))
                || selectors.contains(normalizeSelector(columnRule.column()));
    }

    private void markResolved(EncryptColumnRule columnRule, Set<String> unresolvedSelectors) {
        unresolvedSelectors.remove(normalizeSelector(columnRule.property()));
        unresolvedSelectors.remove(normalizeSelector(columnRule.column()));
    }

    private String describeSelectors(Set<String> unresolvedSelectors) {
        java.util.List<String> selectors = new java.util.ArrayList<>();
        for (String selector : unresolvedSelectors) {
            selectors.add(rawSelectorsByKey.getOrDefault(selector, selector));
        }
        return selectors.toString();
    }

    private String normalizeSelector(String selector) {
        return StringUtils.isBlank(selector) ? null : NameUtils.normalizeIdentifier(selector);
    }
}
