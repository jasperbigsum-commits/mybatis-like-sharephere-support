package io.github.jasper.mybatis.encrypt.config;

import io.github.jasper.mybatis.encrypt.core.metadata.FieldStorageMode;
import io.github.jasper.mybatis.encrypt.util.NameUtils;
import io.github.jasper.mybatis.encrypt.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * 数据库加密插件的外部配置。
 *
 * <p>包含全局开关、SQL 方言选择、自动扫描实体以及按表配置的字段加密规则。</p>
 */
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
     * 辅助查询算法计算摘要时附加的十六进制盐值。
     */
    private String defaultHexSlat = "";

    /**
     * 是否在启动阶段扫描实体注解并预注册加密元数据。
     */
    private boolean scanEntityAnnotations = true;

    /**
     * 实体扫描器使用的基础包列表；为空时使用 Spring Boot 自动配置包。
     */
    private List<String> scanPackages = new ArrayList<>();

    /**
     * 改写 SQL 时用于引用标识符的 SQL 方言。
     */
    private SqlDialect sqlDialect = SqlDialect.MYSQL;

    /**
     * 按数据源名称匹配的 SQL 方言覆盖规则。
     */
    private List<DataSourceDialectRuleProperties> datasourceDialects = new ArrayList<>();

    /**
     * 迁移模块默认策略。
     */
    private MigrationProperties migration = new MigrationProperties();

    /**
     * 配置中显式声明的按表加密规则列表。
     */
    private List<TableRuleProperties> tables = new ArrayList<>();

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
     * 返回默认十六进制盐值。
     *
     * @return 默认十六进制盐值
     */
    public String getDefaultHexSlat() {
        return defaultHexSlat;
    }

    /**
     * 设置默认十六进制盐值。
     *
     * @param defaultHexSlat 默认十六进制盐值
     */
    public void setDefaultHexSlat(String defaultHexSlat) {
        this.defaultHexSlat = defaultHexSlat;
    }

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
        return resolveSqlDialect(SqlDialectContextHolder.currentDataSourceName());
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
     * 返回全局默认 SQL 方言，不应用数据源覆盖规则。
     *
     * @return 全局默认 SQL 方言
     */
    public SqlDialect getDefaultSqlDialect() {
        return sqlDialect;
    }

    /**
     * 返回数据源方言覆盖规则。
     *
     * @return 数据源方言覆盖规则
     */
    public List<DataSourceDialectRuleProperties> getDatasourceDialects() {
        return datasourceDialects;
    }

    /**
     * 设置数据源方言覆盖规则。
     *
     * @param datasourceDialects 数据源方言覆盖规则
     */
    public void setDatasourceDialects(List<DataSourceDialectRuleProperties> datasourceDialects) {
        this.datasourceDialects = datasourceDialects;
    }

    /**
     * 返回迁移模块默认策略。
     *
     * @return 迁移模块默认策略
     */
    public MigrationProperties getMigration() {
        return migration;
    }

    /**
     * 设置迁移模块默认策略。
     *
     * @param migration 迁移模块默认策略
     */
    public void setMigration(MigrationProperties migration) {
        this.migration = migration;
    }

    /**
     * 返回表级规则列表。
     *
     * @return 表规则配置列表
     */
    public List<TableRuleProperties> getTables() {
        return tables;
    }

    /**
     * 设置表级规则列表。
     *
     * @param tables 表规则配置列表
     */
    public void setTables(List<TableRuleProperties> tables) {
        this.tables = tables;
    }

    /**
     * 按数据源名称解析当前应使用的 SQL 方言。
     *
     * <p>支持通过 {@code |} 分隔多个名称或通配模式，例如
     * {@code master|archive-*|reporting?}。</p>
     *
     * @param dataSourceName 数据源 bean 名称
     * @return 命中的 SQL 方言；未命中时回退到全局默认值
     */
    public SqlDialect resolveSqlDialect(String dataSourceName) {
        if (StringUtils.isNotBlank(dataSourceName)) {
            for (DataSourceDialectRuleProperties rule : datasourceDialects) {
                if (rule != null && rule.matches(dataSourceName) && rule.getSqlDialect() != null) {
                    return rule.getSqlDialect();
                }
            }
        }
        return sqlDialect;
    }

    /**
     * 判断某个表是否被全局迁移策略排除。
     *
     * @param tableName 物理表名
     * @return 命中排除规则时返回 {@code true}
     */
    public boolean isMigrationTableExcluded(String tableName) {
        if (migration == null || StringUtils.isBlank(tableName)) {
            return false;
        }
        return migration.matchesExcludedTable(tableName);
    }

    /**
     * 根据全局迁移模板规则解析备份列名。
     *
     * @param tableName 主表名
     * @param property 加密属性名
     * @param column 明文字段列名
     * @return 解析得到的备份列名；未命中规则时返回 {@code null}
     */
    public String resolveMigrationBackupColumn(String tableName, String property, String column) {
        if (migration == null) {
            return null;
        }
        return migration.resolveBackupColumn(tableName, property, column);
    }

    /**
     * 解析某张表默认应使用的迁移游标列。
     *
     * @param tableName 物理表名
     * @return 迁移游标列
     */
    public List<String> resolveMigrationCursorColumns(String tableName) {
        if (migration == null) {
            return Collections.singletonList("id");
        }
        return migration.resolveCursorColumns(tableName);
    }

    private static boolean matchesPipePattern(String pipePattern, String candidate) {
        if (StringUtils.isBlank(pipePattern) || StringUtils.isBlank(candidate)) {
            return false;
        }
        String normalizedCandidate = candidate.trim().toLowerCase(Locale.ROOT);
        String[] parts = pipePattern.split("\\|");
        for (String part : parts) {
            String normalizedPattern = part == null ? "" : part.trim().toLowerCase(Locale.ROOT);
            if (normalizedPattern.isEmpty()) {
                continue;
            }
            if (globMatches(normalizedPattern, normalizedCandidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean globMatches(String pattern, String candidate) {
        StringBuilder regex = new StringBuilder();
        for (int index = 0; index < pattern.length(); index++) {
            char current = pattern.charAt(index);
            if (current == '*') {
                regex.append(".*");
            } else if (current == '?') {
                regex.append('.');
            } else if ("\\.[]{}()+-^$|".indexOf(current) >= 0) {
                regex.append('\\').append(current);
            } else {
                regex.append(current);
            }
        }
        return candidate.matches(regex.toString());
    }

    private static String normalizeTableName(String tableName) {
        return StringUtils.isBlank(tableName) ? null : NameUtils.normalizeIdentifier(tableName);
    }

    private static String trimToNull(String value) {
        return StringUtils.isBlank(value) ? null : value.trim();
    }

    /**
     * 数据源名称与 SQL 方言的匹配规则。
     */
    public static class DataSourceDialectRuleProperties {

        /**
         * 数据源 bean 名称 pipe 模式。
         */
        private String datasourceNamePattern;

        /**
         * 命中后的 SQL 方言。
         */
        private SqlDialect sqlDialect;

        /**
         * 返回数据源名称匹配模式。
         *
         * @return 数据源名称匹配模式
         */
        public String getDatasourceNamePattern() {
            return datasourceNamePattern;
        }

        /**
         * 设置数据源名称匹配模式。
         *
         * @param datasourceNamePattern 数据源名称匹配模式
         */
        public void setDatasourceNamePattern(String datasourceNamePattern) {
            this.datasourceNamePattern = datasourceNamePattern;
        }

        /**
         * 返回命中后的 SQL 方言。
         *
         * @return SQL 方言
         */
        public SqlDialect getSqlDialect() {
            return sqlDialect;
        }

        /**
         * 设置命中后的 SQL 方言。
         *
         * @param sqlDialect SQL 方言
         */
        public void setSqlDialect(SqlDialect sqlDialect) {
            this.sqlDialect = sqlDialect;
        }

        /**
         * 判断当前数据源名称是否命中该规则。
         *
         * @param dataSourceName 数据源名称
         * @return 命中时返回 {@code true}
         */
        public boolean matches(String dataSourceName) {
            return matchesPipePattern(datasourceNamePattern, dataSourceName);
        }
    }

    /**
     * 迁移模块默认策略。
     */
    public static class MigrationProperties {

        /**
         * 默认迁移游标列。
         */
        private List<String> defaultCursorColumns = Collections.singletonList("id");

        /**
         * checkpoint 持久化目录。
         */
        private String checkpointDirectory = "migration-state";

        /**
         * 默认迁移批大小。
         */
        private int batchSize = 200;

        /**
         * 默认是否启用写后校验。
         */
        private boolean verifyAfterWrite = true;

        /**
         * 全局排除的迁移表。
         */
        private List<String> excludeTables = new ArrayList<>();

        /**
         * 备份列模板规则，按声明顺序匹配。
         */
        private List<BackupColumnTemplateRuleProperties> backupColumnTemplates = new ArrayList<>();

        /**
         * 按表覆盖默认游标列的规则。
         */
        private List<TableCursorRuleProperties> cursorRules = new ArrayList<>();

        /**
         * 返回默认迁移游标列。
         *
         * @return 默认迁移游标列
         */
        public List<String> getDefaultCursorColumns() {
            return defaultCursorColumns;
        }

        /**
         * 设置默认迁移游标列。
         *
         * @param defaultCursorColumns 默认迁移游标列
         */
        public void setDefaultCursorColumns(List<String> defaultCursorColumns) {
            this.defaultCursorColumns = defaultCursorColumns;
        }

        /**
         * 返回 checkpoint 持久化目录。
         *
         * @return checkpoint 持久化目录
         */
        public String getCheckpointDirectory() {
            return checkpointDirectory;
        }

        /**
         * 设置 checkpoint 持久化目录。
         *
         * @param checkpointDirectory checkpoint 持久化目录
         */
        public void setCheckpointDirectory(String checkpointDirectory) {
            this.checkpointDirectory = checkpointDirectory;
        }

        /**
         * 返回默认迁移批大小。
         *
         * @return 默认迁移批大小
         */
        public int getBatchSize() {
            return batchSize;
        }

        /**
         * 设置默认迁移批大小。
         *
         * @param batchSize 默认迁移批大小
         */
        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        /**
         * 返回默认是否启用写后校验。
         *
         * @return 写后校验开关
         */
        public boolean isVerifyAfterWrite() {
            return verifyAfterWrite;
        }

        /**
         * 设置默认是否启用写后校验。
         *
         * @param verifyAfterWrite 写后校验开关
         */
        public void setVerifyAfterWrite(boolean verifyAfterWrite) {
            this.verifyAfterWrite = verifyAfterWrite;
        }

        /**
         * 返回全局排除的迁移表规则。
         *
         * @return 全局排除的迁移表规则
         */
        public List<String> getExcludeTables() {
            return excludeTables;
        }

        /**
         * 设置全局排除的迁移表规则。
         *
         * @param excludeTables 全局排除的迁移表规则
         */
        public void setExcludeTables(List<String> excludeTables) {
            this.excludeTables = excludeTables;
        }

        /**
         * 返回备份列模板规则。
         *
         * @return 备份列模板规则
         */
        public List<BackupColumnTemplateRuleProperties> getBackupColumnTemplates() {
            return backupColumnTemplates;
        }

        /**
         * 设置备份列模板规则。
         *
         * @param backupColumnTemplates 备份列模板规则
         */
        public void setBackupColumnTemplates(List<BackupColumnTemplateRuleProperties> backupColumnTemplates) {
            this.backupColumnTemplates = backupColumnTemplates;
        }

        /**
         * 返回按表覆盖默认游标列的规则。
         *
         * @return 按表覆盖默认游标列的规则
         */
        public List<TableCursorRuleProperties> getCursorRules() {
            return cursorRules;
        }

        /**
         * 设置按表覆盖默认游标列的规则。
         *
         * @param cursorRules 按表覆盖默认游标列的规则
         */
        public void setCursorRules(List<TableCursorRuleProperties> cursorRules) {
            this.cursorRules = cursorRules;
        }

        boolean matchesExcludedTable(String tableName) {
            String normalizedTable = normalizeTableName(tableName);
            if (normalizedTable == null) {
                return false;
            }
            for (String excludeTable : excludeTables) {
                if (matchesPipePattern(excludeTable, normalizedTable)) {
                    return true;
                }
            }
            return false;
        }

        String resolveBackupColumn(String tableName, String property, String column) {
            String normalizedTable = normalizeTableName(tableName);
            String normalizedColumn = trimToNull(column);
            String normalizedProperty = trimToNull(property);
            for (BackupColumnTemplateRuleProperties rule : backupColumnTemplates) {
                if (rule == null || !rule.matches(normalizedTable, normalizedProperty, normalizedColumn)) {
                    continue;
                }
                return rule.render(normalizedTable, normalizedProperty, normalizedColumn);
            }
            return null;
        }

        List<String> resolveCursorColumns(String tableName) {
            String normalizedTable = normalizeTableName(tableName);
            for (TableCursorRuleProperties rule : cursorRules) {
                if (rule == null || !rule.matches(normalizedTable)) {
                    continue;
                }
                if (rule.getCursorColumns() != null && !rule.getCursorColumns().isEmpty()) {
                    return rule.getCursorColumns();
                }
            }
            return defaultCursorColumns == null || defaultCursorColumns.isEmpty()
                    ? Collections.singletonList("id")
                    : defaultCursorColumns;
        }
    }

    /**
     * 按表覆盖默认游标列的规则。
     */
    public static class TableCursorRuleProperties {

        /**
         * 匹配主表名的 pipe 模式。
         */
        private String tablePattern = "*";

        /**
         * 命中后的游标列集合。
         */
        private List<String> cursorColumns = new ArrayList<String>();

        /**
         * 返回匹配主表名的 pipe 模式。
         *
         * @return 主表匹配模式
         */
        public String getTablePattern() {
            return tablePattern;
        }

        /**
         * 设置匹配主表名的 pipe 模式。
         *
         * @param tablePattern 主表匹配模式
         */
        public void setTablePattern(String tablePattern) {
            this.tablePattern = tablePattern;
        }

        /**
         * 返回命中后的游标列集合。
         *
         * @return 游标列集合
         */
        public List<String> getCursorColumns() {
            return cursorColumns;
        }

        /**
         * 设置命中后的游标列集合。
         *
         * @param cursorColumns 游标列集合
         */
        public void setCursorColumns(List<String> cursorColumns) {
            this.cursorColumns = cursorColumns;
        }

        boolean matches(String tableName) {
            return matchesPipePattern(tablePattern, tableName);
        }
    }

    /**
     * 迁移备份列模板规则。
     */
    public static class BackupColumnTemplateRuleProperties {

        /**
         * 匹配主表名的 pipe 模式。
         */
        private String tablePattern = "*";

        /**
         * 匹配字段选择器的 pipe 模式；同时匹配属性名和源列名。
         */
        private String fieldPattern = "*";

        /**
         * 备份列模板，支持 `${table}`、`${property}`、`${column}`。
         */
        private String template = "${column}_backup";

        /**
         * 返回匹配主表名的 pipe 模式。
         *
         * @return 主表匹配模式
         */
        public String getTablePattern() {
            return tablePattern;
        }

        /**
         * 设置匹配主表名的 pipe 模式。
         *
         * @param tablePattern 主表匹配模式
         */
        public void setTablePattern(String tablePattern) {
            this.tablePattern = tablePattern;
        }

        /**
         * 返回匹配字段选择器的 pipe 模式。
         *
         * @return 字段匹配模式
         */
        public String getFieldPattern() {
            return fieldPattern;
        }

        /**
         * 设置匹配字段选择器的 pipe 模式。
         *
         * @param fieldPattern 字段匹配模式
         */
        public void setFieldPattern(String fieldPattern) {
            this.fieldPattern = fieldPattern;
        }

        /**
         * 返回备份列模板。
         *
         * @return 备份列模板
         */
        public String getTemplate() {
            return template;
        }

        /**
         * 设置备份列模板。
         *
         * @param template 备份列模板
         */
        public void setTemplate(String template) {
            this.template = template;
        }

        boolean matches(String tableName, String property, String column) {
            if (!matchesPipePattern(tablePattern, firstNonBlank(tableName, ""))) {
                return false;
            }
            return matchesPipePattern(fieldPattern, firstNonBlank(property, ""))
                    || matchesPipePattern(fieldPattern, firstNonBlank(column, ""));
        }

        String render(String tableName, String property, String column) {
            String resolvedTemplate = trimToNull(template);
            if (resolvedTemplate == null) {
                return null;
            }
            Map<String, String> variables = new LinkedHashMap<String, String>();
            variables.put("table", firstNonBlank(tableName, ""));
            variables.put("property", firstNonBlank(property, ""));
            variables.put("column", firstNonBlank(column, ""));
            String rendered = resolvedTemplate;
            for (Map.Entry<String, String> entry : variables.entrySet()) {
                rendered = rendered.replace("${" + entry.getKey() + "}", entry.getValue());
            }
            String normalizedRendered = trimToNull(rendered);
            return normalizedRendered == null ? null : normalizedRendered;
        }

        private String firstNonBlank(String first, String second) {
            return StringUtils.isNotBlank(first) ? first : second;
        }
    }

    /**
     * 从配置绑定得到的表级规则定义。
     */
    public static class TableRuleProperties {

        /**
         * 物理表名。
         */
        private String table;

        /**
         * 字段规则集合。
         */
        private List<FieldRuleProperties> fields = new ArrayList<>();

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
         * 返回字段规则列表。
         *
         * @return 字段规则列表
         */
        public List<FieldRuleProperties> getFields() {
            return fields;
        }

        /**
         * 设置字段规则列表。
         *
         * @param fields 字段规则列表
         */
        public void setFields(List<FieldRuleProperties> fields) {
            this.fields = fields;
        }
    }

    /**
     * 字段级加密规则定义。
     */
    public static class FieldRuleProperties {

        /**
         * 实体属性名；省略时优先按 {@link #column} 推断驼峰属性名。
         */
        private String property;

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
         * 外部存储表中的物理主键列。
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
         * 面向对外返回的存储态脱敏列。
         */
        private String maskedColumn;

        /**
         * 存储态脱敏列的算法 bean 名称。
         */
        private String maskedAlgorithm = "normalizedLike";

        /**
         * 返回实体属性名。
         *
         * @return 实体属性名
         */
        public String getProperty() {
            return property;
        }

        /**
         * 设置实体属性名。
         *
         * @param property 实体属性名
         */
        public void setProperty(String property) {
            this.property = property;
        }

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
         * 返回独立表物理主键列。
         *
         * @return 独立表物理主键列名
         */
        public String getStorageIdColumn() {
            return storageIdColumn;
        }

        /**
         * 设置独立表物理主键列。
         *
         * @param storageIdColumn 独立表物理主键列名
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

        /**
         * 返回存储态脱敏列。
         *
         * @return 存储态脱敏列名
         */
        public String getMaskedColumn() {
            return maskedColumn;
        }

        /**
         * 设置存储态脱敏列。
         *
         * @param maskedColumn 存储态脱敏列名
         */
        public void setMaskedColumn(String maskedColumn) {
            this.maskedColumn = maskedColumn;
        }

        /**
         * 返回存储态脱敏算法 bean 名称。
         *
         * @return 存储态脱敏算法 bean 名称
         */
        public String getMaskedAlgorithm() {
            return maskedAlgorithm;
        }

        /**
         * 设置存储态脱敏算法 bean 名称。
         *
         * @param maskedAlgorithm 存储态脱敏算法 bean 名称
         */
        public void setMaskedAlgorithm(String maskedAlgorithm) {
            this.maskedAlgorithm = maskedAlgorithm;
        }
    }
}
