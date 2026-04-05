package tech.jasper.mybatis.encrypt.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import tech.jasper.mybatis.encrypt.core.metadata.FieldStorageMode;

@ConfigurationProperties(prefix = "mybatis.encrypt")
/**
 * 数据库字段加密插件配置。
 *
 * <p>配置模型分为全局开关和按表、按字段的规则定义两层。该类只负责承载外部配置，
 * 不承担规则合并或推导逻辑，真正的规则组装由 {@code EncryptMetadataRegistry} 完成。</p>
 */
public class DatabaseEncryptionProperties {

    private boolean enabled = true;

    private boolean failOnMissingRule = true;

    private boolean logMaskedSql = true;

    private String defaultCipherKey = "change-me-before-production";

    private boolean scanEntityAnnotations = true;

    private List<String> scanPackages = List.of();

    private SqlDialect sqlDialect = SqlDialect.MYSQL;

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

    public static class TableRuleProperties {

        private String table;

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

    public static class FieldRuleProperties {

        private String column;

        private FieldStorageMode storageMode = FieldStorageMode.SAME_TABLE;

        private String storageTable;

        private String storageColumn;

        private String sourceIdProperty = "id";

        private String sourceIdColumn;

        private String storageIdColumn;

        private String cipherAlgorithm = "sm4";

        private String assistedQueryColumn;

        private String assistedQueryAlgorithm = "sm3";

        private String likeQueryColumn;

        private String likeQueryAlgorithm = "normalizedLike";

        public String getColumn() {
            return column;
        }

        public void setColumn(String column) {
            this.column = column;
        }

        public String getCipherAlgorithm() {
            return cipherAlgorithm;
        }

        public void setCipherAlgorithm(String cipherAlgorithm) {
            this.cipherAlgorithm = cipherAlgorithm;
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
