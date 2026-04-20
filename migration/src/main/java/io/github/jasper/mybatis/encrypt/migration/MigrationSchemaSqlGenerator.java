package io.github.jasper.mybatis.encrypt.migration;

import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.config.SqlDialect;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptTableRule;
import io.github.jasper.mybatis.encrypt.migration.plan.EntityMigrationPlanFactory;
import io.github.jasper.mybatis.encrypt.util.NameUtils;
import io.github.jasper.mybatis.encrypt.util.StringUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 批量生成迁移前所需的字段 DDL。
 *
 * <p>该工具会读取当前数据库中主表明文字段的结构，按规则推导出密文字段、等值查询字段、
 * LIKE 字段和备份字段应使用的目标类型与长度，并输出 {@code ALTER TABLE ADD/MODIFY}
 * 语句列表，便于上线前批量准备迁移所需字段。</p>
 *
 * <p>默认长度策略：</p>
 * <ul>
 *     <li>hash / assisted 字段：固定 128</li>
 *     <li>like 字段：与原字段等长</li>
 *     <li>cipher 字段：默认占位长度 + 原字段长度 * 权重</li>
 * </ul>
 */
public final class MigrationSchemaSqlGenerator {

    private final DataSource dataSource;
    private final String dataSourceName;
    private final EncryptMetadataRegistry metadataRegistry;
    private final DatabaseEncryptionProperties properties;
    private final EntityMigrationPlanFactory planFactory;
    private final SizingOptions sizingOptions;
    private final SqlDialect dialect;

    /**
     * 使用默认属性和默认长度策略创建生成器。
     *
     * @param dataSource 数据源
     * @param metadataRegistry 加密元数据注册表
     */
    public MigrationSchemaSqlGenerator(DataSource dataSource,
                                       EncryptMetadataRegistry metadataRegistry) {
        this(dataSource, null, metadataRegistry, new DatabaseEncryptionProperties(), new SizingOptions());
    }

    /**
     * 使用指定属性和默认长度策略创建生成器。
     *
     * @param dataSource 数据源
     * @param metadataRegistry 加密元数据注册表
     * @param properties 加密配置
     */
    public MigrationSchemaSqlGenerator(DataSource dataSource,
                                       EncryptMetadataRegistry metadataRegistry,
                                       DatabaseEncryptionProperties properties) {
        this(dataSource, null, metadataRegistry, properties, new SizingOptions());
    }

    /**
     * 使用完整参数创建生成器。
     *
     * @param dataSource 数据源
     * @param dataSourceName 数据源名称，用于命中方言覆盖规则
     * @param metadataRegistry 加密元数据注册表
     * @param properties 加密配置
     * @param sizingOptions 列长度策略
     */
    public MigrationSchemaSqlGenerator(DataSource dataSource,
                                       String dataSourceName,
                                       EncryptMetadataRegistry metadataRegistry,
                                       DatabaseEncryptionProperties properties,
                                       SizingOptions sizingOptions) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource must not be null");
        }
        if (metadataRegistry == null) {
            throw new IllegalArgumentException("metadataRegistry must not be null");
        }
        this.dataSource = dataSource;
        this.dataSourceName = dataSourceName;
        this.metadataRegistry = metadataRegistry;
        this.properties = properties == null ? new DatabaseEncryptionProperties() : properties;
        this.planFactory = new EntityMigrationPlanFactory(metadataRegistry, this.properties);
        this.sizingOptions = sizingOptions == null ? new SizingOptions() : sizingOptions;
        this.dialect = this.properties.resolveSqlDialect(dataSourceName);
    }

    /**
     * 为指定实体生成 DDL。
     *
     * @param entityType 实体类型
     * @return DDL 列表
     */
    public List<String> generateForEntity(Class<?> entityType) {
        return generateForEntity(entityType, null);
    }

    /**
     * 为指定实体生成 DDL，并支持按字段裁剪。
     *
     * @param entityType 实体类型
     * @param builderCustomizer 自定义选择字段、备份列等
     * @return DDL 列表
     */
    public List<String> generateForEntity(Class<?> entityType,
                                          Consumer<EntityMigrationDefinition.Builder> builderCustomizer) {
        EncryptTableRule tableRule = metadataRegistry.findByEntity(entityType)
                .orElseThrow(() -> new MigrationDefinitionException(MigrationErrorCode.METADATA_RULE_MISSING,
                        "Missing registered entity encryption rule: " + entityType.getName()));
        List<String> cursorColumns = defaultCursorColumns(tableRule.getTableName());
        EntityMigrationDefinition.Builder builder = EntityMigrationDefinition.builder(
                entityType,
                cursorColumns.get(0),
                cursorColumns.size() <= 1 ? new String[0]
                        : cursorColumns.subList(1, cursorColumns.size()).toArray(new String[0])
        );
        if (builderCustomizer != null) {
            builderCustomizer.accept(builder);
        }
        return generate(builder.build());
    }

    /**
     * 为指定物理表生成 DDL。
     *
     * @param tableName 物理表名
     * @return DDL 列表
     */
    public List<String> generateForTable(String tableName) {
        return generateForTable(tableName, null);
    }

    /**
     * 为指定物理表生成 DDL，并支持按字段裁剪。
     *
     * @param tableName 物理表名
     * @param builderCustomizer 自定义选择字段、备份列等
     * @return DDL 列表
     */
    public List<String> generateForTable(String tableName,
                                         Consumer<EntityMigrationDefinition.Builder> builderCustomizer) {
        List<String> cursorColumns = defaultCursorColumns(tableName);
        EntityMigrationDefinition.Builder builder = EntityMigrationDefinition.builder(
                tableName,
                cursorColumns.get(0),
                cursorColumns.size() <= 1 ? new String[0]
                        : cursorColumns.subList(1, cursorColumns.size()).toArray(new String[0])
        );
        if (builderCustomizer != null) {
            builderCustomizer.accept(builder);
        }
        return generate(builder.build());
    }

    /**
     * 根据显式迁移定义生成 DDL。
     *
     * @param definition 迁移定义
     * @return DDL 列表
     */
    public List<String> generate(EntityMigrationDefinition definition) {
        EntityMigrationPlan plan = planFactory.create(definition, dataSourceName);
        return generate(plan);
    }

    /**
     * 按当前元数据注册表中已注册的全部表，批量生成 DDL。
     *
     * @return 扁平 DDL 列表
     */
    public List<String> generateAllRegisteredTables() {
        return generateAllRegisteredTables(null);
    }

    /**
     * 按当前元数据注册表中已注册的全部表，批量生成 DDL。
     *
     * @param builderCustomizer 对每张表迁移定义统一追加的自定义配置
     * @return 扁平 DDL 列表
     */
    public List<String> generateAllRegisteredTables(Consumer<EntityMigrationDefinition.Builder> builderCustomizer) {
        List<String> statements = new ArrayList<String>();
        for (List<String> ddl : generateAllRegisteredTablesGrouped(builderCustomizer).values()) {
            statements.addAll(ddl);
        }
        return statements;
    }

    /**
     * 按当前元数据注册表中已注册的全部表，分组生成 DDL。
     *
     * @return 按表分组的 DDL
     */
    public Map<String, List<String>> generateAllRegisteredTablesGrouped() {
        return generateAllRegisteredTablesGrouped(null);
    }

    /**
     * 按当前元数据注册表中已注册的全部表，分组生成 DDL。
     *
     * @param builderCustomizer 对每张表迁移定义统一追加的自定义配置
     * @return 按表分组的 DDL
     */
    public Map<String, List<String>> generateAllRegisteredTablesGrouped(
            Consumer<EntityMigrationDefinition.Builder> builderCustomizer) {
        LinkedHashMap<String, List<String>> statementsByTable = new LinkedHashMap<String, List<String>>();
        for (String tableName : metadataRegistry.getRegisteredTableNames()) {
            if (properties.isMigrationTableExcluded(tableName)) {
                continue;
            }
            List<String> ddl = generateForTable(tableName, builderCustomizer);
            if (!ddl.isEmpty()) {
                statementsByTable.put(tableName, ddl);
            }
        }
        return statementsByTable;
    }

    private List<String> generate(EntityMigrationPlan plan) {
        SchemaSnapshot snapshot = loadSnapshot();
        LinkedHashMap<String, TableRequirement> requirements = new LinkedHashMap<String, TableRequirement>();
        for (EntityMigrationColumnPlan columnPlan : plan.getColumnPlans()) {
            ColumnMetadata sourceColumn = snapshot.requireColumn(plan.getTableName(), columnPlan.getSourceColumn());
            if (columnPlan.isStoredInSeparateTable()) {
                String storageTable = columnPlan.getStorageTable();
                if (!snapshot.hasTable(storageTable)) {
                    registerRequirement(requirements, storageTable, columnPlan.getStorageIdColumn(),
                            referenceIdType(), true);
                }
                registerRequirement(requirements, storageTable, columnPlan.getStorageColumn(),
                        cipherType(sourceColumn));
                if (StringUtils.isNotBlank(columnPlan.getAssistedQueryColumn())) {
                    registerRequirement(requirements, storageTable, columnPlan.getAssistedQueryColumn(), hashType());
                }
                if (StringUtils.isNotBlank(columnPlan.getLikeQueryColumn())) {
                    registerRequirement(requirements, storageTable, columnPlan.getLikeQueryColumn(),
                            likeType(sourceColumn));
                }
            } else {
                registerRequirement(requirements, plan.getTableName(), columnPlan.getStorageColumn(),
                        cipherType(sourceColumn));
                if (StringUtils.isNotBlank(columnPlan.getAssistedQueryColumn())) {
                    registerRequirement(requirements, plan.getTableName(), columnPlan.getAssistedQueryColumn(), hashType());
                }
                if (StringUtils.isNotBlank(columnPlan.getLikeQueryColumn())) {
                    registerRequirement(requirements, plan.getTableName(), columnPlan.getLikeQueryColumn(),
                            likeType(sourceColumn));
                }
            }
            if (columnPlan.shouldWriteBackup()) {
                registerRequirement(requirements, plan.getTableName(), columnPlan.getBackupColumn(),
                        backupType(sourceColumn));
            }
        }

        List<String> statements = new ArrayList<String>();
        for (TableRequirement tableRequirement : requirements.values()) {
            if (!snapshot.hasTable(tableRequirement.tableName)) {
                statements.add(createTableSql(tableRequirement));
                continue;
            }
            for (ColumnRequirement requirement : tableRequirement.columns.values()) {
                ColumnMetadata existing = snapshot.findColumn(requirement.tableName, requirement.columnName);
                if (existing == null) {
                    statements.add(addColumnSql(requirement.tableName, requirement.columnName, requirement.columnType));
                    continue;
                }
                if (!existing.satisfies(requirement.columnType)) {
                    statements.add(modifyColumnSql(requirement.tableName, requirement.columnName, requirement.columnType));
                }
            }
        }
        return statements;
    }

    private List<String> defaultCursorColumns(String tableName) {
        List<String> cursorColumns = properties.resolveMigrationCursorColumns(tableName);
        if (cursorColumns == null || cursorColumns.isEmpty()) {
            throw new MigrationDefinitionException(MigrationErrorCode.DEFINITION_INVALID,
                    "cursorColumns must not be empty for table: " + tableName);
        }
        return cursorColumns;
    }

    private void registerRequirement(Map<String, TableRequirement> requirements,
                                     String tableName,
                                     String columnName,
                                     ColumnType columnType) {
        registerRequirement(requirements, tableName, columnName, columnType, false);
    }

    private void registerRequirement(Map<String, TableRequirement> requirements,
                                     String tableName,
                                     String columnName,
                                     ColumnType columnType,
                                     boolean primaryKey) {
        if (StringUtils.isBlank(tableName) || StringUtils.isBlank(columnName) || columnType == null) {
            return;
        }
        String normalizedTable = NameUtils.normalizeIdentifier(tableName);
        TableRequirement tableRequirement = requirements.get(normalizedTable);
        if (tableRequirement == null) {
            tableRequirement = new TableRequirement(tableName);
            requirements.put(normalizedTable, tableRequirement);
        }
        tableRequirement.register(columnName, columnType, primaryKey);
    }

    private ColumnType cipherType(ColumnMetadata sourceColumn) {
        int sourceLength = sourceColumn.resolveCharacterLength(sizingOptions.getFallbackCharacterLength());
        int length = sizingOptions.getCipherColumnBaseLength()
                + (sourceLength - sizingOptions.getCipherColumnMinCharLength()) * sizingOptions.getCipherColumnLengthWeight();
        return ColumnType.variableCharacter(length, dialect);
    }

    private ColumnType hashType() {
        return ColumnType.variableCharacter(sizingOptions.getHashColumnLength(), dialect);
    }

    private ColumnType likeType(ColumnMetadata sourceColumn) {
        return sourceColumn.toComparableCharacterType(dialect, sizingOptions.getFallbackCharacterLength());
    }

    private ColumnType backupType(ColumnMetadata sourceColumn) {
        return sourceColumn.toComparableType(dialect, sizingOptions.getFallbackCharacterLength());
    }

    private ColumnType referenceIdType() {
        return ColumnType.variableCharacter(sizingOptions.getReferenceIdColumnLength(), dialect);
    }

    private SchemaSnapshot loadSnapshot() {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            Map<String, TableMetadata> tables = new LinkedHashMap<String, TableMetadata>();
            try (ResultSet resultSet = metadata.getColumns(connection.getCatalog(), null, null, null)) {
                while (resultSet.next()) {
                    String tableName = resultSet.getString("TABLE_NAME");
                    String normalizedTable = NameUtils.normalizeIdentifier(tableName);
                    if (normalizedTable == null) {
                        continue;
                    }
                    TableMetadata table = tables.get(normalizedTable);
                    if (table == null) {
                        table = new TableMetadata(tableName);
                        tables.put(normalizedTable, table);
                    }
                    table.addColumn(ColumnMetadata.from(resultSet));
                }
            }
            return new SchemaSnapshot(tables);
        } catch (SQLException ex) {
            throw new MigrationException(MigrationErrorCode.GENERAL_FAILURE,
                    "Failed to inspect database schema metadata.", ex);
        }
    }

    private String addColumnSql(String tableName, String columnName, ColumnType columnType) {
        String quotedTable = dialect.quote(tableName);
        String quotedColumn = dialect.quote(columnName);
        switch (dialect) {
            case ORACLE12:
            case DM:
                return "alter table " + quotedTable + " add (" + quotedColumn + " " + columnType.renderedType + ")";
            default:
                return "alter table " + quotedTable + " add column " + quotedColumn + " " + columnType.renderedType;
        }
    }

    private String modifyColumnSql(String tableName, String columnName, ColumnType columnType) {
        String quotedTable = dialect.quote(tableName);
        String quotedColumn = dialect.quote(columnName);
        switch (dialect) {
            case ORACLE12:
            case DM:
                return "alter table " + quotedTable + " modify (" + quotedColumn + " " + columnType.renderedType + ")";
            default:
                return "alter table " + quotedTable + " modify column " + quotedColumn + " " + columnType.renderedType;
        }
    }

    private String createTableSql(TableRequirement tableRequirement) {
        if (dialect == SqlDialect.CLICKHOUSE) {
            throw new MigrationException(MigrationErrorCode.DEFINITION_INVALID,
                    "DDL generator cannot auto-create ClickHouse tables because ENGINE / ORDER BY clauses "
                            + "must be supplied manually. table=" + tableRequirement.tableName);
        }
        StringBuilder sql = new StringBuilder();
        sql.append("create table ").append(dialect.quote(tableRequirement.tableName)).append(" (");
        boolean first = true;
        for (ColumnRequirement requirement : tableRequirement.columns.values()) {
            if (!first) {
                sql.append(", ");
            }
            sql.append(dialect.quote(requirement.columnName)).append(' ').append(requirement.columnType.renderedType);
            if (tableRequirement.primaryKeyColumns.size() == 1
                    && tableRequirement.primaryKeyColumns.contains(NameUtils.normalizeIdentifier(requirement.columnName))) {
                sql.append(" primary key");
            }
            first = false;
        }
        if (tableRequirement.primaryKeyColumns.size() > 1) {
            sql.append(", primary key (");
            boolean firstKey = true;
            for (String primaryKeyColumn : tableRequirement.primaryKeyColumns) {
                if (!firstKey) {
                    sql.append(", ");
                }
                sql.append(dialect.quote(primaryKeyColumn));
                firstKey = false;
            }
            sql.append(')');
        }
        sql.append(')');
        return sql.toString();
    }

    /**
     * 列长度策略。
     */
    public static final class SizingOptions {

        private int hashColumnLength = 64;
        private int fallbackCharacterLength = 255;
        private int cipherColumnBaseLength = 64;
        private int cipherColumnMinCharLength = 18;
        private int cipherColumnLengthWeight = 1;
        private int referenceIdColumnLength = 64;

        /**
         * 返回辅助查询哈希列长度。
         *
         * @return 哈希列长度
         */
        public int getHashColumnLength() {
            return hashColumnLength;
        }

        /**
         * 设置辅助查询哈希列长度。
         *
         * @param hashColumnLength 哈希列长度，必须大于 0
         */
        public void setHashColumnLength(int hashColumnLength) {
            this.hashColumnLength = positive(hashColumnLength, "hashColumnLength");
        }

        /**
         * 返回回退字符列长度。
         *
         * @return 回退字符列长度
         */
        public int getFallbackCharacterLength() {
            return fallbackCharacterLength;
        }

        /**
         * 设置回退字符列长度。
         *
         * @param fallbackCharacterLength 回退字符列长度，必须大于 0
         */
        public void setFallbackCharacterLength(int fallbackCharacterLength) {
            this.fallbackCharacterLength = positive(fallbackCharacterLength, "fallbackCharacterLength");
        }

        /**
         * 返回密文列基础长度。
         *
         * @return 密文列基础长度
         */
        public int getCipherColumnBaseLength() {
            return cipherColumnBaseLength;
        }

        /**
         * 设置密文列基础长度。
         *
         * @param cipherColumnBaseLength 密文列基础长度，必须大于 0
         */
        public void setCipherColumnBaseLength(int cipherColumnBaseLength) {
            this.cipherColumnBaseLength = positive(cipherColumnBaseLength, "cipherColumnBaseLength");
        }

        /**
         * 返回加密字段的最小参考基数生成
         * @return 最小参考基数大小
         */
        public int getCipherColumnMinCharLength() {
            return cipherColumnMinCharLength;
        }

        /**
         * 设置加密字段的最小参考基数生成
         * @param cipherColumnMinCharLength 最小参考基数大小
         */
        public void setCipherColumnMinCharLength(int cipherColumnMinCharLength) {
            this.cipherColumnMinCharLength = cipherColumnMinCharLength;
        }

        /**
         * 返回密文列长度权重。
         *
         * @return 密文列长度权重
         */
        public int getCipherColumnLengthWeight() {
            return cipherColumnLengthWeight;
        }

        /**
         * 设置密文列长度权重。
         *
         * @param cipherColumnLengthWeight 密文列长度权重，必须大于 0
         */
        public void setCipherColumnLengthWeight(int cipherColumnLengthWeight) {
            this.cipherColumnLengthWeight = positive(cipherColumnLengthWeight, "cipherColumnLengthWeight");
        }

        /**
         * 返回独立表引用列长度。
         *
         * @return 引用列长度
         */
        public int getReferenceIdColumnLength() {
            return referenceIdColumnLength;
        }

        /**
         * 设置独立表引用列长度。
         *
         * @param referenceIdColumnLength 引用列长度，必须大于 0
         */
        public void setReferenceIdColumnLength(int referenceIdColumnLength) {
            this.referenceIdColumnLength = positive(referenceIdColumnLength, "referenceIdColumnLength");
        }

        private int positive(int value, String fieldName) {
            if (value <= 0) {
                throw new IllegalArgumentException(fieldName + " must be greater than zero");
            }
            return value;
        }
    }

    private static final class SchemaSnapshot {

        private final Map<String, TableMetadata> tables;

        private SchemaSnapshot(Map<String, TableMetadata> tables) {
            this.tables = tables;
        }

        private boolean hasTable(String tableName) {
            return tables.containsKey(NameUtils.normalizeIdentifier(tableName));
        }

        private ColumnMetadata requireColumn(String tableName, String columnName) {
            ColumnMetadata column = findColumn(tableName, columnName);
            if (column != null) {
                return column;
            }
            throw new MigrationDefinitionException(MigrationErrorCode.DEFINITION_INVALID,
                    "Missing source column for schema SQL generation: table=" + tableName + ", column=" + columnName);
        }

        private ColumnMetadata findColumn(String tableName, String columnName) {
            TableMetadata table = tables.get(NameUtils.normalizeIdentifier(tableName));
            return table == null ? null : table.findColumn(columnName);
        }
    }

    private static final class TableMetadata {

        private final String tableName;
        private final Map<String, ColumnMetadata> columns = new LinkedHashMap<String, ColumnMetadata>();

        private TableMetadata(String tableName) {
            this.tableName = tableName;
        }

        private void addColumn(ColumnMetadata column) {
            columns.put(NameUtils.normalizeIdentifier(column.columnName), column);
        }

        private ColumnMetadata findColumn(String columnName) {
            return columns.get(NameUtils.normalizeIdentifier(columnName));
        }
    }

    private static final class ColumnMetadata {

        private final String tableName;
        private final String columnName;
        private final String typeName;
        private final int columnSize;
        private final int decimalDigits;

        private ColumnMetadata(String tableName,
                               String columnName,
                               String typeName,
                               int columnSize,
                               int decimalDigits) {
            this.tableName = tableName;
            this.columnName = columnName;
            this.typeName = typeName;
            this.columnSize = columnSize;
            this.decimalDigits = decimalDigits;
        }

        private static ColumnMetadata from(ResultSet resultSet) throws SQLException {
            return new ColumnMetadata(
                    resultSet.getString("TABLE_NAME"),
                    resultSet.getString("COLUMN_NAME"),
                    resultSet.getString("TYPE_NAME"),
                    resultSet.getInt("COLUMN_SIZE"),
                    resultSet.getInt("DECIMAL_DIGITS")
            );
        }

        private int resolveCharacterLength(int fallbackCharacterLength) {
            return columnSize > 0 ? columnSize : fallbackCharacterLength;
        }

        private ColumnType toComparableType(SqlDialect dialect, int fallbackCharacterLength) {
            String family = typeFamily();
            if ("varchar".equals(family)) {
                return ColumnType.variableCharacter(resolveCharacterLength(fallbackCharacterLength), dialect);
            }
            if ("char".equals(family)) {
                return ColumnType.fixedCharacter(resolveCharacterLength(fallbackCharacterLength), dialect);
            }
            if (columnSize > 0 && decimalDigits > 0) {
                return ColumnType.generic(family, renderGenericType(typeName, columnSize, decimalDigits), columnSize, decimalDigits);
            }
            if (columnSize > 0 && supportsLength(family)) {
                return ColumnType.generic(family, renderGenericType(typeName, columnSize, 0), columnSize, null);
            }
            return ColumnType.generic(family, typeName, null, null);
        }

        private ColumnType toComparableCharacterType(SqlDialect dialect, int fallbackCharacterLength) {
            String family = typeFamily();
            if ("char".equals(family)) {
                return ColumnType.fixedCharacter(resolveCharacterLength(fallbackCharacterLength), dialect);
            }
            return ColumnType.variableCharacter(resolveCharacterLength(fallbackCharacterLength), dialect);
        }

        private boolean satisfies(ColumnType targetType) {
            if (!typeFamily().equals(targetType.family)) {
                return false;
            }
            if (targetType.length != null && columnSize > 0 && columnSize < targetType.length) {
                return false;
            }
            return targetType.scale == null || decimalDigits >= targetType.scale;
        }

        private String typeFamily() {
            String normalized = normalizeTypeName(typeName);
            if (normalized.contains("character varying") || normalized.contains("varchar")
                    || normalized.contains("varchar2") || normalized.contains("text")
                    || normalized.contains("clob")) {
                return "varchar";
            }
            if ("character".equals(normalized) || "char".equals(normalized) || normalized.endsWith(" char")) {
                return "char";
            }
            if (normalized.contains("decimal") || normalized.contains("numeric") || normalized.contains("number")) {
                return "decimal";
            }
            return normalized;
        }

        private String normalizeTypeName(String value) {
            return value == null ? "" : value.trim().toLowerCase();
        }

        private boolean supportsLength(String family) {
            return "decimal".equals(family) || "numeric".equals(family) || "number".equals(family);
        }

        private String renderGenericType(String typeName, int length, int scale) {
            if (scale > 0) {
                return typeName + "(" + length + ", " + scale + ")";
            }
            return typeName + "(" + length + ")";
        }
    }

    private static final class ColumnType {

        private final String family;
        private final String renderedType;
        private final Integer length;
        private final Integer scale;

        private ColumnType(String family, String renderedType, Integer length, Integer scale) {
            this.family = family;
            this.renderedType = renderedType;
            this.length = length;
            this.scale = scale;
        }

        private static ColumnType variableCharacter(int length, SqlDialect dialect) {
            return new ColumnType("varchar", varyingTypeName(dialect) + "(" + length + ")", length, null);
        }

        private static ColumnType fixedCharacter(int length, SqlDialect dialect) {
            return new ColumnType("char", "char(" + length + ")", length, null);
        }

        private static ColumnType generic(String family, String renderedType, Integer length, Integer scale) {
            return new ColumnType(family, renderedType, length, scale);
        }

        private static String varyingTypeName(SqlDialect dialect) {
            switch (dialect) {
                case ORACLE12:
                case DM:
                    return "varchar2";
                default:
                    return "varchar";
            }
        }
    }

    private static final class ColumnRequirement {

        private final String tableName;
        private final String columnName;
        private final ColumnType columnType;

        private ColumnRequirement(String tableName, String columnName, ColumnType columnType) {
            this.tableName = tableName;
            this.columnName = columnName;
            this.columnType = columnType;
        }

        private ColumnRequirement merge(ColumnType incoming) {
            if (!columnType.family.equals(incoming.family)) {
                throw new MigrationDefinitionException(MigrationErrorCode.DEFINITION_INVALID,
                        "Conflicting schema requirement for column: " + tableName + "." + columnName);
            }
            if (columnType.length == null || incoming.length == null) {
                return this;
            }
            if (incoming.length > columnType.length) {
                return new ColumnRequirement(tableName, columnName, incoming);
            }
            return this;
        }
    }

    private static final class TableRequirement {

        private final String tableName;
        private final LinkedHashMap<String, ColumnRequirement> columns =
                new LinkedHashMap<String, ColumnRequirement>();
        private final List<String> primaryKeyColumns = new ArrayList<String>();

        private TableRequirement(String tableName) {
            this.tableName = tableName;
        }

        private void register(String columnName, ColumnType columnType, boolean primaryKey) {
            String normalizedColumn = NameUtils.normalizeIdentifier(columnName);
            ColumnRequirement existing = columns.get(normalizedColumn);
            if (existing == null) {
                columns.put(normalizedColumn, new ColumnRequirement(tableName, columnName, columnType));
            } else {
                columns.put(normalizedColumn, existing.merge(columnType));
            }
            if (primaryKey && !primaryKeyColumns.contains(normalizedColumn)) {
                primaryKeyColumns.add(normalizedColumn);
            }
        }
    }
}
