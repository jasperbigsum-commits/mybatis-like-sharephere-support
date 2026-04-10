package io.github.jasper.mybatis.encrypt.config;

import io.github.jasper.mybatis.encrypt.core.metadata.FieldStorageMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据库加密插件的外部配置。
 *
 * <p>包含全局开关、SQL 方言选择、自动扫描实体以及按表配置的字段加密规则。</p>
 */
@ConfigurationProperties(prefix = "mybatis.encrypt")
public class UserDatabaseEncryptionProperties {

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
     * 做HASH计算的时候所用到的盐，若存在则附加SLAT
     */
    private String defaultHexSlat = "";

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

    /**
     * 返回插件总开关。
     *
     * @return 是否启用插件
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置插件总开关。
     *
     * @param enabled 是否启用插件
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 返回缺少规则时是否快速失败。
     *
     * @return 是否快速失败
     */
    public boolean isFailOnMissingRule() {
        return failOnMissingRule;
    }

    /**
     * 设置缺少规则时是否快速失败。
     *
     * @param failOnMissingRule 是否快速失败
     */
    public void setFailOnMissingRule(boolean failOnMissingRule) {
        this.failOnMissingRule = failOnMissingRule;
    }

    /**
     * 返回是否记录脱敏后的改写 SQL。
     *
     * @return 是否输出脱敏 SQL 日志
     */
    public boolean isLogMaskedSql() {
        return logMaskedSql;
    }

    /**
     * 设置是否记录脱敏后的改写 SQL。
     *
     * @param logMaskedSql 是否输出脱敏 SQL 日志
     */
    public void setLogMaskedSql(boolean logMaskedSql) {
        this.logMaskedSql = logMaskedSql;
    }

    /**
     * 返回默认密钥。
     *
     * @return 默认密钥材料
     */
    public String getDefaultCipherKey() {
        return defaultCipherKey;
    }

    /**
     * 设置默认密钥。
     *
     * @param defaultCipherKey 默认密钥材料
     */
    public void setDefaultCipherKey(String defaultCipherKey) {
        this.defaultCipherKey = defaultCipherKey;
    }

    /**
     * 返回 默认盐
     * @return 默认盐
     */
    public String getDefaultHexSlat() {
        return defaultHexSlat;
    }

    /**
     * 设置 默认盐
     * @param defaultHexSlat 默认盐
     */
    public void setDefaultHexSlat(String defaultHexSlat) {}

    /**
     * 返回是否启用实体注解扫描。
     *
     * @return 是否扫描实体注解
     */
    public boolean isScanEntityAnnotations() {
        return scanEntityAnnotations;
    }

    /**
     * 设置是否启用实体注解扫描。
     *
     * @param scanEntityAnnotations 是否扫描实体注解
     */
    public void setScanEntityAnnotations(boolean scanEntityAnnotations) {
        this.scanEntityAnnotations = scanEntityAnnotations;
    }

    /**
     * 返回实体扫描包列表。
     *
     * @return 需要扫描的基础包
     */
    public List<String> getScanPackages() {
        return scanPackages;
    }

    /**
     * 设置实体扫描包列表。
     *
     * @param scanPackages 需要扫描的基础包
     */
    public void setScanPackages(List<String> scanPackages) {
        this.scanPackages = scanPackages;
    }

    /**
     * 返回 SQL 方言。
     *
     * @return 当前 SQL 方言
     */
    public SqlDialect getSqlDialect() {
        return sqlDialect;
    }

    /**
     * 设置 SQL 方言。
     *
     * @param sqlDialect SQL 方言
     */
    public void setSqlDialect(SqlDialect sqlDialect) {
        this.sqlDialect = sqlDialect;
    }

    /**
     * 返回表级规则映射。
     *
     * @return 表规则配置
     */
    public Map<String, TableRuleProperties> getTables() {
        return tables;
    }

    /**
     * 设置表级规则映射。
     *
     * @param tables 表规则配置
     */
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

        /**
         * 返回物理表名。
         *
         * @return 物理表名
         */
        public String getTable() {
            return table;
        }

        /**
         * 设置物理表名。
         *
         * @param table 物理表名
         */
        public void setTable(String table) {
            this.table = table;
        }

        /**
         * 返回字段规则集合。
         *
         * @return 字段规则映射
         */
        public Map<String, FieldRuleProperties> getFields() {
            return fields;
        }

        /**
         * 设置字段规则集合。
         *
         * @param fields 字段规则映射
         */
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

        /**
         * 返回原始业务列名。
         *
         * @return 原始业务列名
         */
        public String getColumn() {
            return column;
        }

        /**
         * 设置原始业务列名。
         *
         * @param column 原始业务列名
         */
        public void setColumn(String column) {
            this.column = column;
        }

        /**
         * 返回字段存储模式。
         *
         * @return 字段存储模式
         */
        public FieldStorageMode getStorageMode() {
            return storageMode;
        }

        /**
         * 设置字段存储模式。
         *
         * @param storageMode 字段存储模式
         */
        public void setStorageMode(FieldStorageMode storageMode) {
            this.storageMode = storageMode;
        }

        /**
         * 返回独立表表名。
         *
         * @return 独立表表名
         */
        public String getStorageTable() {
            return storageTable;
        }

        /**
         * 设置独立表表名。
         *
         * @param storageTable 独立表表名
         */
        public void setStorageTable(String storageTable) {
            this.storageTable = storageTable;
        }

        /**
         * 返回密文存储列。
         *
         * @return 密文存储列名
         */
        public String getStorageColumn() {
            return storageColumn;
        }

        /**
         * 设置密文存储列。
         *
         * @param storageColumn 密文存储列名
         */
        public void setStorageColumn(String storageColumn) {
            this.storageColumn = storageColumn;
        }

        /**
         * 返回独立表 id 列。
         *
         * @return 独立表 id 列名
         */
        public String getStorageIdColumn() {
            return storageIdColumn;
        }

        /**
         * 设置独立表 id 列。
         *
         * @param storageIdColumn 独立表 id 列名
         */
        public void setStorageIdColumn(String storageIdColumn) {
            this.storageIdColumn = storageIdColumn;
        }

        /**
         * 返回加密算法 bean 名称。
         *
         * @return 加密算法 bean 名称
         */
        public String getCipherAlgorithm() {
            return cipherAlgorithm;
        }

        /**
         * 设置加密算法 bean 名称。
         *
         * @param cipherAlgorithm 加密算法 bean 名称
         */
        public void setCipherAlgorithm(String cipherAlgorithm) {
            this.cipherAlgorithm = cipherAlgorithm;
        }

        /**
         * 返回辅助查询列。
         *
         * @return 辅助查询列名
         */
        public String getAssistedQueryColumn() {
            return assistedQueryColumn;
        }

        /**
         * 设置辅助查询列。
         *
         * @param assistedQueryColumn 辅助查询列名
         */
        public void setAssistedQueryColumn(String assistedQueryColumn) {
            this.assistedQueryColumn = assistedQueryColumn;
        }

        /**
         * 返回辅助查询算法 bean 名称。
         *
         * @return 辅助查询算法 bean 名称
         */
        public String getAssistedQueryAlgorithm() {
            return assistedQueryAlgorithm;
        }

        /**
         * 设置辅助查询算法 bean 名称。
         *
         * @param assistedQueryAlgorithm 辅助查询算法 bean 名称
         */
        public void setAssistedQueryAlgorithm(String assistedQueryAlgorithm) {
            this.assistedQueryAlgorithm = assistedQueryAlgorithm;
        }

        /**
         * 返回 LIKE 查询列。
         *
         * @return LIKE 查询列名
         */
        public String getLikeQueryColumn() {
            return likeQueryColumn;
        }

        /**
         * 设置 LIKE 查询列。
         *
         * @param likeQueryColumn LIKE 查询列名
         */
        public void setLikeQueryColumn(String likeQueryColumn) {
            this.likeQueryColumn = likeQueryColumn;
        }

        /**
         * 返回 LIKE 查询算法 bean 名称。
         *
         * @return LIKE 查询算法 bean 名称
         */
        public String getLikeQueryAlgorithm() {
            return likeQueryAlgorithm;
        }

        /**
         * 设置 LIKE 查询算法 bean 名称。
         *
         * @param likeQueryAlgorithm LIKE 查询算法 bean 名称
         */
        public void setLikeQueryAlgorithm(String likeQueryAlgorithm) {
            this.likeQueryAlgorithm = likeQueryAlgorithm;
        }
    }
}

