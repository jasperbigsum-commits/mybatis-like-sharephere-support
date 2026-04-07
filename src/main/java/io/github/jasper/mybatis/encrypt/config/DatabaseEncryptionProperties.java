package io.github.jasper.mybatis.encrypt.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import io.github.jasper.mybatis.encrypt.core.metadata.FieldStorageMode;

/**
 * 数据库加密插件的外部配置。
 *
 * <p>包含全局开关、SQL 方言选择、自动扫描实体以及按表配置的字段加密规则。</p>
 */
@ConfigurationProperties(prefix = "mybatis.encrypt")
public class DatabaseEncryptionProperties {

    /**
     * 插件总开关。
     */
    private boolean enabled = true;

    /**
     * 缺少元数据时是否快速失败，而不是静默跳过加密处理。
     */
    private boolean failOnMissingRule = true;

    /**
     * 是否记录带参数脱敏值的改写后 SQL。
     */
    private boolean logMaskedSql = true;

    /**
     * 内置加密算法在没有自定义 bean 覆盖时使用的默认密钥。
     */
    private String defaultCipherKey = "change-me-before-production";

    /**
     * 是否在启动阶段扫描实体注解并预注册加密元数据。
     */
    private boolean scanEntityAnnotations = true;

    /**
     * 实体扫描器使用的基础包列表；为空时使用 Spring Boot 自动配置包。
     */
    private List<String> scanPackages = List.of();

    /**
     * 改写 SQL 时用于引用标识符的 SQL 方言。
     */
    private SqlDialect sqlDialect = SqlDialect.MYSQL;

    /**
     * 配置中显式声明的按表加密规则，键可以是逻辑名或表别名。
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
     * 从配置绑定得到的表级规则定义。
     */
    public static class TableRuleProperties {

        /**
         * 物理表名；省略时外层 map 的键会作为默认表名。
         */
        private String table;

        /**
         * 以实体属性名为键的字段规则集合。
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
     * 字段级加密规则定义。
     */
    public static class FieldRuleProperties {

        /**
         * 应用 SQL 使用的原始业务列名；配置省略时使用属性名的 snake_case。
         */
        private String column;

        /**
         * 加密字段的存储模式。
         */
        private FieldStorageMode storageMode = FieldStorageMode.SAME_TABLE;

        /**
         * 当 {@link #storageMode} 为 {@code SEPARATE_TABLE} 时使用的外部存储表。
         */
        private String storageTable;

        /**
         * 实际密文存储列；省略时默认使用 column。
         */
        private String storageColumn;

        /**
         * 用作业务行标识的实体属性；省略时由框架内部推断。
         */
        private String sourceIdProperty = "";

        /**
         * 业务表中的来源标识列。
         */
        private String sourceIdColumn;

        /**
         * 外部存储表中的标识列。
         */
        private String storageIdColumn;

        /**
         * 加密算法 bean 名称。
         */
        private String cipherAlgorithm = "sm4";

        /**
         * 用于存储哈希或确定性标记值的辅助等值查询列。
         */
        private String assistedQueryColumn;

        /**
         * 辅助等值查询算法 bean 名称。
         */
        private String assistedQueryAlgorithm = "sm3";

        /**
         * 可选的 LIKE 查询列。
         */
        private String likeQueryColumn;

        /**
         * LIKE 查询算法 bean 名称。
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

