package io.github.jasper.mybatis.encrypt.core.mask;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import io.github.jasper.mybatis.encrypt.util.StringUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * JDBC implementation that batch-loads stored masked values from encrypted storage tables.
 *
 * <p>The resolver is used only at the controller response boundary. It receives decrypted field
 * records, transforms the plaintext value with the field's {@code assistedQueryAlgorithm}, and
 * queries the configured {@code maskedColumn} by {@code assistedQueryColumn}. The returned value is
 * assumed to be already masked at the storage layer and is therefore applied as-is by
 * {@link SensitiveDataMasker}.</p>
 *
 * <p>Queries are grouped by datasource, table and field rule, then split into bounded IN batches.
 * SQL failures are treated as best-effort misses so the caller can fall back to algorithm or
 * annotation masking.</p>
 */
public final class JdbcStoredSensitiveValueResolver implements StoredSensitiveValueResolver {

    private static final int LOOKUP_BATCH_SIZE = 200;

    private final Map<String, DataSource> dataSources;
    private final AlgorithmRegistry algorithmRegistry;
    private final DatabaseEncryptionProperties properties;
    private final DataSource defaultDataSource;
    private final String defaultDataSourceName;

    /**
     * Creates a resolver backed by one or more data sources.
     *
     * <p>When a record does not carry an explicit datasource name, the first datasource in the map
     * is used as the default. A {@code null} or empty datasource map disables stored-value lookup.</p>
     *
     * @param dataSources available data sources
     * @param algorithmRegistry algorithm registry
     * @param properties encryption properties
     */
    public JdbcStoredSensitiveValueResolver(Map<String, DataSource> dataSources,
                                            AlgorithmRegistry algorithmRegistry,
                                            DatabaseEncryptionProperties properties) {
        this.dataSources = dataSources == null
                ? Collections.<String, DataSource>emptyMap()
                : new LinkedHashMap<String, DataSource>(dataSources);
        this.algorithmRegistry = algorithmRegistry;
        this.properties = properties == null ? new DatabaseEncryptionProperties() : properties;
        Map.Entry<String, DataSource> first = this.dataSources.entrySet().stream().findFirst().orElse(null);
        this.defaultDataSourceName = first == null ? null : first.getKey();
        this.defaultDataSource = first == null ? null : first.getValue();
    }

    @Override
    public Map<SensitiveDataContext.SensitiveRecord, String> resolve(Collection<SensitiveDataContext.SensitiveRecord> records) {
        if (records == null || records.isEmpty() || algorithmRegistry == null || defaultDataSource == null) {
            return Collections.emptyMap();
        }
        Map<SensitiveDataContext.SensitiveRecord, String> resolved = new LinkedHashMap<SensitiveDataContext.SensitiveRecord, String>();
        Map<LookupGroupKey, Map<String, List<SensitiveDataContext.SensitiveRecord>>> grouped =
                new LinkedHashMap<LookupGroupKey, Map<String, List<SensitiveDataContext.SensitiveRecord>>>();
        for (SensitiveDataContext.SensitiveRecord record : records) {
            if (record == null || StringUtils.isBlank(record.value())) {
                continue;
            }
            EncryptColumnRule rule = record.rule();
            if (rule == null || !rule.hasMaskedColumn() || !rule.hasAssistedQueryColumn()) {
                continue;
            }
            String tableName = resolveLookupTable(rule);
            if (StringUtils.isBlank(tableName)) {
                continue;
            }
            String dataSourceName = normalizeDataSourceName(record.dataSourceName());
            String lookupValue = algorithmRegistry.assisted(rule.assistedQueryAlgorithm()).transform(record.value());
            LookupGroupKey key = new LookupGroupKey(dataSourceName, tableName, rule);
            Map<String, List<SensitiveDataContext.SensitiveRecord>> byLookup =
                    grouped.computeIfAbsent(key, ignored -> new LinkedHashMap<String, List<SensitiveDataContext.SensitiveRecord>>());
            byLookup.computeIfAbsent(lookupValue, ignored -> new ArrayList<SensitiveDataContext.SensitiveRecord>()).add(record);
        }
        for (Map.Entry<LookupGroupKey, Map<String, List<SensitiveDataContext.SensitiveRecord>>> entry : grouped.entrySet()) {
            resolveGroup(entry.getKey(), entry.getValue(), resolved);
        }
        return resolved;
    }

    private void resolveGroup(LookupGroupKey key,
                              Map<String, List<SensitiveDataContext.SensitiveRecord>> recordsByLookup,
                              Map<SensitiveDataContext.SensitiveRecord, String> resolved) {
        DataSource dataSource = resolveDataSource(key.dataSourceName);
        if (dataSource == null || recordsByLookup.isEmpty()) {
            return;
        }
        List<String> lookupValues = new ArrayList<String>(recordsByLookup.keySet());
        for (int start = 0; start < lookupValues.size(); start += LOOKUP_BATCH_SIZE) {
            int end = Math.min(start + LOOKUP_BATCH_SIZE, lookupValues.size());
            loadBatch(dataSource, key, lookupValues.subList(start, end), recordsByLookup, resolved);
        }
    }

    private void loadBatch(DataSource dataSource,
                           LookupGroupKey key,
                           List<String> lookupValues,
                           Map<String, List<SensitiveDataContext.SensitiveRecord>> recordsByLookup,
                           Map<SensitiveDataContext.SensitiveRecord, String> resolved) {
        if (lookupValues.isEmpty()) {
            return;
        }
        String quote = properties.resolveSqlDialect(key.dataSourceName).quote(key.rule.assistedQueryColumn());
        String masked = properties.resolveSqlDialect(key.dataSourceName).quote(key.rule.maskedColumn());
        String table = properties.resolveSqlDialect(key.dataSourceName).quote(key.tableName);
        StringBuilder placeholders = new StringBuilder();
        for (int index = 0; index < lookupValues.size(); index++) {
            if (index > 0) {
                placeholders.append(", ");
            }
            placeholders.append('?');
        }
        String sql = "select " + quote + ", " + masked
                + " from " + table
                + " where " + quote + " in (" + placeholders + ")";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < lookupValues.size(); index++) {
                statement.setString(index + 1, lookupValues.get(index));
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String lookupValue = resultSet.getString(1);
                    String maskedValue = resultSet.getString(2);
                    List<SensitiveDataContext.SensitiveRecord> matched = recordsByLookup.get(lookupValue);
                    if (matched == null || maskedValue == null) {
                        continue;
                    }
                    for (SensitiveDataContext.SensitiveRecord record : matched) {
                        resolved.put(record, maskedValue);
                    }
                }
            }
        } catch (SQLException ignore) {
            // Masked value resolution is best-effort; fallback strategies handle unresolved fields.
        }
    }

    private String resolveLookupTable(EncryptColumnRule rule) {
        if (rule.isStoredInSeparateTable()) {
            return rule.storageTable();
        }
        return rule.table();
    }

    private DataSource resolveDataSource(String dataSourceName) {
        if (StringUtils.isNotBlank(dataSourceName)) {
            DataSource dataSource = dataSources.get(dataSourceName);
            if (dataSource != null) {
                return dataSource;
            }
        }
        return defaultDataSource;
    }

    private String normalizeDataSourceName(String dataSourceName) {
        return StringUtils.isNotBlank(dataSourceName) ? dataSourceName : defaultDataSourceName;
    }

    private static final class LookupGroupKey {

        private final String dataSourceName;
        private final String tableName;
        private final EncryptColumnRule rule;

        private LookupGroupKey(String dataSourceName, String tableName, EncryptColumnRule rule) {
            this.dataSourceName = dataSourceName;
            this.tableName = tableName;
            this.rule = rule;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof LookupGroupKey)) {
                return false;
            }
            LookupGroupKey that = (LookupGroupKey) other;
            return Objects.equals(dataSourceName, that.dataSourceName)
                    && Objects.equals(tableName, that.tableName)
                    && Objects.equals(rule, that.rule);
        }

        @Override
        public int hashCode() {
            return Objects.hash(dataSourceName, tableName, rule);
        }
    }
}
