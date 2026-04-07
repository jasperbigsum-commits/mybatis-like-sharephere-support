package io.github.jasper.mybatis.encrypt.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import io.github.jasper.mybatis.encrypt.core.metadata.FieldStorageMode;

/**
 * External configuration for the database encryption plugin.
 *
 * <p>The properties include global switches, SQL dialect selection, automatic entity scanning,
 * and per-table field encryption rules.</p>
 */
@ConfigurationProperties(prefix = "mybatis.encrypt")
public class DatabaseEncryptionProperties {

    /**
     * Master switch for the plugin.
     */
    private boolean enabled = true;

    /**
     * Whether missing metadata should fail fast instead of silently skipping encryption.
     */
    private boolean failOnMissingRule = true;

    /**
     * Whether rewritten SQL should be logged with masked parameter values.
     */
    private boolean logMaskedSql = true;

    /**
     * Default key used by built-in cipher algorithms when no custom bean configuration overrides it.
     */
    private String defaultCipherKey = "change-me-before-production";

    /**
     * Enables startup scanning of entity annotations to pre-register encryption metadata.
     */
    private boolean scanEntityAnnotations = true;

    /**
     * Base packages used by the entity scanner. When empty, Spring Boot auto-configuration packages are used.
     */
    private List<String> scanPackages = List.of();

    /**
     * SQL dialect used for quoting identifiers in rewritten SQL.
     */
    private SqlDialect sqlDialect = SqlDialect.MYSQL;

    /**
     * Explicit per-table encryption rules keyed by logical name or table alias in configuration.
     */
    private Map<String, TableRuleProperties> tables = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isFailOnMissingRule() {
        return failOnMissingRule;
    }

    public void setFailOnMissingRule(boolean failOnMissingRule) {
        this.failOnMissingRule = failOnMissingRule;
    }

    public boolean isLogMaskedSql() {
        return logMaskedSql;
    }

    public void setLogMaskedSql(boolean logMaskedSql) {
        this.logMaskedSql = logMaskedSql;
    }

    public String getDefaultCipherKey() {
        return defaultCipherKey;
    }

    public void setDefaultCipherKey(String defaultCipherKey) {
        this.defaultCipherKey = defaultCipherKey;
    }

    public boolean isScanEntityAnnotations() {
        return scanEntityAnnotations;
    }

    public void setScanEntityAnnotations(boolean scanEntityAnnotations) {
        this.scanEntityAnnotations = scanEntityAnnotations;
    }

    public List<String> getScanPackages() {
        return scanPackages;
    }

    public void setScanPackages(List<String> scanPackages) {
        this.scanPackages = scanPackages;
    }

    public SqlDialect getSqlDialect() {
        return sqlDialect;
    }

    public void setSqlDialect(SqlDialect sqlDialect) {
        this.sqlDialect = sqlDialect;
    }

    public Map<String, TableRuleProperties> getTables() {
        return tables;
    }

    public void setTables(Map<String, TableRuleProperties> tables) {
        this.tables = tables;
    }

    /**
     * Table-level rule definition bound from configuration.
     */
    public static class TableRuleProperties {

        /**
         * Physical table name. When omitted, the outer map key is treated as the default table name.
         */
        private String table;

        /**
         * Field rules keyed by entity property name.
         */
        private Map<String, FieldRuleProperties> fields = new LinkedHashMap<>();

        public String getTable() {
            return table;
        }

        public void setTable(String table) {
            this.table = table;
        }

        public Map<String, FieldRuleProperties> getFields() {
            return fields;
        }

        public void setFields(Map<String, FieldRuleProperties> fields) {
            this.fields = fields;
        }
    }

    /**
     * Field-level encryption rule definition.
     */
    public static class FieldRuleProperties {

        /**
         * Original business column name used by application SQL. When omitted in configuration, property-name snake_case is used.
         */
        private String column;

        /**
         * Storage mode for the encrypted field.
         */
        private FieldStorageMode storageMode = FieldStorageMode.SAME_TABLE;

        /**
         * External storage table used when {@link #storageMode} is {@code SEPARATE_TABLE}.
         */
        private String storageTable;

        /**
         * Real ciphertext storage column. Defaults to column when omitted.
         */
        private String storageColumn;

        /**
         * Entity property used as the business row identifier. When omitted, it is inferred internally.
         */
        private String sourceIdProperty = "";

        /**
         * Source identifier column in the business table.
         */
        private String sourceIdColumn;

        /**
         * Identifier column in the external storage table.
         */
        private String storageIdColumn;

        /**
         * Cipher algorithm bean name.
         */
        private String cipherAlgorithm = "sm4";

        /**
         * Assisted equality lookup column that stores hash or deterministic token values.
         */
        private String assistedQueryColumn;

        /**
         * Assisted equality algorithm bean name.
         */
        private String assistedQueryAlgorithm = "sm3";

        /**
         * Optional LIKE lookup column.
         */
        private String likeQueryColumn;

        /**
         * LIKE lookup algorithm bean name.
         */
        private String likeQueryAlgorithm = "normalizedLike";

        public String getColumn() {
            return column;
        }

        public void setColumn(String column) {
            this.column = column;
        }

        public FieldStorageMode getStorageMode() {
            return storageMode;
        }

        public void setStorageMode(FieldStorageMode storageMode) {
            this.storageMode = storageMode;
        }

        public String getStorageTable() {
            return storageTable;
        }

        public void setStorageTable(String storageTable) {
            this.storageTable = storageTable;
        }

        public String getStorageColumn() {
            return storageColumn;
        }

        public void setStorageColumn(String storageColumn) {
            this.storageColumn = storageColumn;
        }

        public String getSourceIdProperty() {
            return sourceIdProperty;
        }

        public void setSourceIdProperty(String sourceIdProperty) {
            this.sourceIdProperty = sourceIdProperty;
        }

        public String getSourceIdColumn() {
            return sourceIdColumn;
        }

        public void setSourceIdColumn(String sourceIdColumn) {
            this.sourceIdColumn = sourceIdColumn;
        }

        public String getStorageIdColumn() {
            return storageIdColumn;
        }

        public void setStorageIdColumn(String storageIdColumn) {
            this.storageIdColumn = storageIdColumn;
        }

        public String getCipherAlgorithm() {
            return cipherAlgorithm;
        }

        public void setCipherAlgorithm(String cipherAlgorithm) {
            this.cipherAlgorithm = cipherAlgorithm;
        }

        public String getAssistedQueryColumn() {
            return assistedQueryColumn;
        }

        public void setAssistedQueryColumn(String assistedQueryColumn) {
            this.assistedQueryColumn = assistedQueryColumn;
        }

        public String getAssistedQueryAlgorithm() {
            return assistedQueryAlgorithm;
        }

        public void setAssistedQueryAlgorithm(String assistedQueryAlgorithm) {
            this.assistedQueryAlgorithm = assistedQueryAlgorithm;
        }

        public String getLikeQueryColumn() {
            return likeQueryColumn;
        }

        public void setLikeQueryColumn(String likeQueryColumn) {
            this.likeQueryColumn = likeQueryColumn;
        }

        public String getLikeQueryAlgorithm() {
            return likeQueryAlgorithm;
        }

        public void setLikeQueryAlgorithm(String likeQueryAlgorithm) {
            this.likeQueryAlgorithm = likeQueryAlgorithm;
        }
    }
}

