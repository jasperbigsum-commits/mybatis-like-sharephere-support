package io.github.jasper.mybatis.encrypt.config;

import io.github.jasper.mybatis.encrypt.core.metadata.FieldStorageMode;
import io.github.jasper.mybatis.encrypt.util.NameUtils;
import io.github.jasper.mybatis.encrypt.util.StringUtils;
import lombok.Data;

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
@Data
public class DatabaseEncryptionProperties {

    /**
     * 插件总开关。
     * -- SETTER --
     *  设置插件总开关。
     */
    private boolean enabled = true;

    /**
     * 缺少元数据时是否快速失败，而不是静默跳过加密处理。
     * -- SETTER --
     *  设置缺少规则时是否快速失败。
     */
    private boolean failOnMissingRule = true;

    /**
     * 是否记录带参数脱敏值的改写后 SQL。
     * -- SETTER --
     *  设置是否记录脱敏后的改写 SQL。
     */
    private boolean logMaskedSql = true;

    /**
     * 内置加密算法在没有自定义 bean 覆盖时使用的默认密钥。
     * -- SETTER --
     *  设置默认密钥。
     */
    private String defaultCipherKey = "change-me-before-production";


    /**
     * 辅助查询算法计算摘要时附加的十六进制盐值。
     * -- SETTER --
     *  设置默认十六进制盐值。
     */
    private String defaultHexSlat = "";

    /**
     * 是否在启动阶段扫描实体注解并预注册加密元数据。
     * -- SETTER --
     *  设置是否启用实体注解扫描。
     */
    private boolean scanEntityAnnotations = true;

    /**
     * 实体扫描器使用的基础包列表；为空时使用 Spring Boot 自动配置包。
     * -- SETTER --
     *  设置实体扫描包列表。
     */
    private List<String> scanPackages = new ArrayList<>();

    /**
     * 改写 SQL 时用于引用标识符的 SQL 方言。
     * -- SETTER --
     *  设置 SQL 方言。
     *
     */
    private SqlDialect sqlDialect = SqlDialect.MYSQL;

    /**
     * 按数据源名称匹配的 SQL 方言覆盖规则。
     * -- SETTER --
     *  设置数据源方言覆盖规则。
     *
     */
    private List<DataSourceDialectRuleProperties> datasourceDialects = new ArrayList<>();

    /**
     * 迁移模块默认策略。
     * -- SETTER --
     *  设置迁移模块默认策略。
     */
    private MigrationProperties migration = new MigrationProperties();

    /**
     * 独立表结果回填查询的单批最大 hash 数量。
     * -- SETTER --
     *  设置独立表结果回填查询的单批最大 hash 数量。

     */
    private int separateTableHydrationBatchSize = 2000;

    /**
     * 配置中显式声明的按表加密规则列表。
     * -- SETTER --
     *  设置表级规则列表。

     */
    private List<TableRuleProperties> tables = new ArrayList<>();

    /**
     * 返回 SQL 方言。
     *
     * @return 当前 SQL 方言
     */
    public SqlDialect getSqlDialect() {
        return resolveSqlDialect(SqlDialectContextHolder.currentDataSourceName());
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
        String configuredBackupColumn = resolveConfiguredFieldBackupColumn(tableName, property, column);
        if (StringUtils.isNotBlank(configuredBackupColumn)) {
            return configuredBackupColumn;
        }
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

    private String resolveConfiguredFieldBackupColumn(String tableName, String property, String column) {
        String normalizedTable = normalizeTableName(tableName);
        if (normalizedTable == null) {
            return null;
        }
        String normalizedProperty = NameUtils.normalizeIdentifier(property);
        String normalizedColumn = NameUtils.normalizeIdentifier(column);
        for (TableRuleProperties tableRule : tables) {
            if (tableRule == null || !normalizedTable.equals(normalizeTableName(tableRule.getTable()))) {
                continue;
            }
            for (FieldRuleProperties fieldRule : tableRule.getFields()) {
                if (fieldRule == null || !fieldRule.matches(normalizedProperty, normalizedColumn)) {
                    continue;
                }
                return trimToNull(fieldRule.getBackupColumn());
            }
        }
        return null;
    }

    /**
     * 数据源名称与 SQL 方言的匹配规则。
     */
    @Data
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
    @Data
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
    @Data
    public static class TableCursorRuleProperties {

        /**
         * 匹配主表名的 pipe 模式。
         */
        private String tablePattern = "*";

        /**
         * 命中后的游标列集合。
         */
        private List<String> cursorColumns = new ArrayList<>();



        boolean matches(String tableName) {
            return matchesPipePattern(tablePattern, tableName);
        }
    }

    /**
     * 迁移备份列模板规则。
     */
    @Data
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


        boolean matches(String tableName, String property, String column) {
            if (!matchesPipePattern(tablePattern, firstNonBlank(tableName))) {
                return false;
            }
            return matchesPipePattern(fieldPattern, firstNonBlank(property))
                    || matchesPipePattern(fieldPattern, firstNonBlank(column));
        }

        String render(String tableName, String property, String column) {
            String resolvedTemplate = trimToNull(template);
            if (resolvedTemplate == null) {
                return null;
            }
            Map<String, String> variables = new LinkedHashMap<>();
            variables.put("table", firstNonBlank(tableName));
            variables.put("property", firstNonBlank(property));
            variables.put("column", firstNonBlank(column));
            String rendered = resolvedTemplate;
            for (Map.Entry<String, String> entry : variables.entrySet()) {
                rendered = rendered.replace("${" + entry.getKey() + "}", entry.getValue());
            }
            return trimToNull(rendered);
        }

        private String firstNonBlank(String first) {
            return StringUtils.isNotBlank(first) ? first : "";
        }
    }

    /**
     * 从配置绑定得到的表级规则定义。
     */
    @Data
    public static class TableRuleProperties {

        /**
         * 物理表名。
         */
        private String table;

        /**
         * 字段规则集合。
         */
        private List<FieldRuleProperties> fields = new ArrayList<>();

    }

    /**
     * 字段级加密规则定义。
     */
    @Data
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
         * 迁移覆盖主表原列时使用的明文备份列。
         *
         * <p>全量 DDL 生成入口没有单实体 {@code backupColumn(...)} builder 上下文，
         * 因此字段级配置需要在这里显式承载，才能被 {@code generateAllRegisteredTables()} 自动识别。</p>
         */
        private String backupColumn;

        private boolean matches(String normalizedProperty, String normalizedColumn) {
            String fieldProperty = StringUtils.isNotBlank(property)
                    ? property
                    : NameUtils.columnToProperty(column);
            String fieldColumn = StringUtils.isNotBlank(column)
                    ? column
                    : NameUtils.camelToSnake(property);
            // 属性名和源列名都允许作为备份字段选择器，保持与 builder.backupColumn(...) 的匹配规则一致。
            return sameIdentifier(fieldProperty, normalizedProperty)
                    || sameIdentifier(fieldColumn, normalizedColumn);
        }

        private boolean sameIdentifier(String value, String normalizedExpected) {
            String normalizedValue = NameUtils.normalizeIdentifier(value);
            return normalizedValue != null && normalizedValue.equals(normalizedExpected);
        }
    }
}
