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
 *     <li>hash / assisted 字段：固定 64</li>
 *     <li>like 字段：与原字段等长</li>
 *     <li>cipher 字段：按 UTF-8 最坏字节数估算 GCM 密文 Base64 长度，超出阈值时使用大文本类型</li>
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
        List<String> statements = new ArrayList<>();
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
        SchemaSnapshot snapshot = loadSnapshot();
        LinkedHashMap<String, TableRequirement> requirements = new LinkedHashMap<String, TableRequirement>();
        List<String> orderedMainTables = new ArrayList<String>();
        for (String tableName : metadataRegistry.getRegisteredTableNames()) {
            if (properties.isMigrationTableExcluded(tableName)) {
                continue;
            }
            orderedMainTables.add(tableName);
            collectRequirements(buildPlanForTable(tableName, builderCustomizer), snapshot, requirements, tableName);
        }
        LinkedHashMap<String, List<String>> statementsByTable = new LinkedHashMap<String, List<String>>();
        for (String tableName : orderedMainTables) {
            List<String> ddl = renderStatementsForOwner(snapshot, requirements, tableName);
            if (!ddl.isEmpty()) {
                statementsByTable.put(tableName, ddl);
            }
        }
        return statementsByTable;
    }

    private List<String> generate(EntityMigrationPlan plan) {
        SchemaSnapshot snapshot = loadSnapshot();
        LinkedHashMap<String, TableRequirement> requirements = new LinkedHashMap<String, TableRequirement>();
        collectRequirements(plan, snapshot, requirements, plan.getTableName());
        return renderStatementsForOwner(snapshot, requirements, plan.getTableName());
    }

    private EntityMigrationPlan buildPlanForTable(String tableName,
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
        return planFactory.create(builder.build(), dataSourceName);
    }

    private void collectRequirements(EntityMigrationPlan plan,
                                     SchemaSnapshot snapshot,
                                     Map<String, TableRequirement> requirements,
                                     String ownerMainTable) {
        for (EntityMigrationColumnPlan columnPlan : plan.getColumnPlans()) {
            ColumnMetadata sourceColumn = snapshot.requireColumn(plan.getTableName(), columnPlan.getSourceColumn());
            if (columnPlan.isStoredInSeparateTable()) {
                // 独立表迁移会把主表原字段覆盖成 hash/ref，因此主表源列本身也必须至少容纳 hash 长度。
                registerRequirement(requirements, ownerMainTable, plan.getTableName(), columnPlan.getSourceColumn(),
                        hashType(), false, null);
                String storageTable = columnPlan.getStorageTable();
                String previousColumn = columnPlan.getStorageIdColumn();
                if (!snapshot.hasTable(storageTable)) {
                    registerRequirement(requirements, ownerMainTable, storageTable, columnPlan.getStorageIdColumn(),
                            referenceIdType(), true);
                }
                previousColumn = registerRequirement(requirements, ownerMainTable, storageTable, columnPlan.getStorageColumn(),
                        cipherType(sourceColumn), false, previousColumn);
                if (StringUtils.isNotBlank(columnPlan.getAssistedQueryColumn())) {
                    previousColumn = registerRequirement(requirements, ownerMainTable, storageTable, columnPlan.getAssistedQueryColumn(),
                            hashType(), false, previousColumn);
                }
                if (StringUtils.isNotBlank(columnPlan.getLikeQueryColumn())) {
                    previousColumn = registerRequirement(requirements, ownerMainTable, storageTable, columnPlan.getLikeQueryColumn(),
                            likeType(sourceColumn), false, previousColumn);
                }
                if (columnPlan.hasDistinctMaskedColumn()) {
                    registerRequirement(requirements, ownerMainTable, storageTable, columnPlan.getMaskedColumn(),
                            likeType(sourceColumn), false, previousColumn);
                }
            } else {
                String previousColumn = columnPlan.getSourceColumn();
                previousColumn = registerRequirement(requirements, ownerMainTable, plan.getTableName(), columnPlan.getStorageColumn(),
                        cipherType(sourceColumn), false, previousColumn);
                if (StringUtils.isNotBlank(columnPlan.getAssistedQueryColumn())) {
                    previousColumn = registerRequirement(requirements, ownerMainTable, plan.getTableName(),
                            columnPlan.getAssistedQueryColumn(), hashType(), false, previousColumn);
                }
                if (StringUtils.isNotBlank(columnPlan.getLikeQueryColumn())) {
                    previousColumn = registerRequirement(requirements, ownerMainTable, plan.getTableName(),
                            columnPlan.getLikeQueryColumn(), likeType(sourceColumn), false, previousColumn);
                }
                if (columnPlan.hasDistinctMaskedColumn()) {
                    previousColumn = registerRequirement(requirements, ownerMainTable, plan.getTableName(),
                            columnPlan.getMaskedColumn(), likeType(sourceColumn), false, previousColumn);
                }
                if (columnPlan.shouldWriteBackup()) {
                    registerRequirement(requirements, ownerMainTable, plan.getTableName(), columnPlan.getBackupColumn(),
                            backupType(sourceColumn), false, previousColumn);
                }
                continue;
            }
            if (columnPlan.shouldWriteBackup()) {
                registerRequirement(requirements, ownerMainTable, plan.getTableName(), columnPlan.getBackupColumn(),
                        backupType(sourceColumn), false, columnPlan.getSourceColumn());
            }
        }
    }

    private List<String> renderStatementsForOwner(SchemaSnapshot snapshot,
                                                  Map<String, TableRequirement> requirements,
                                                  String ownerMainTable) {
        List<String> statements = new ArrayList<>();
        // 先补主表上的备份列或主表扩展列，再输出缺失独立表的 create table 语句；
        // 这样导出的执行顺序更贴近真实迁移前准备步骤，避免“已配置 backupColumn 但要等到最后才出现”的误判。
        emitExistingTableStatements(statements, snapshot, requirements, ownerMainTable, true, false);
        emitMissingTableStatements(statements, snapshot, requirements, ownerMainTable);
        emitExistingTableStatements(statements, snapshot, requirements, ownerMainTable, false, true);
        return statements;
    }

    private void emitExistingTableStatements(List<String> statements,
                                             SchemaSnapshot snapshot,
                                             Map<String, TableRequirement> requirements,
                                             String ownerMainTable,
                                             boolean mainTableOnly,
                                             boolean externalTableOnly) {
        for (TableRequirement tableRequirement : requirements.values()) {
            if (!tableRequirement.belongsTo(ownerMainTable)) {
                continue;
            }
            if (mainTableOnly && !tableRequirement.isMainTable()) {
                continue;
            }
            if (externalTableOnly && tableRequirement.isMainTable()) {
                continue;
            }
            if (!snapshot.hasTable(tableRequirement.tableName)) {
                continue;
            }
            for (ColumnRequirement requirement : tableRequirement.columns.values()) {
                ColumnMetadata existing = snapshot.findColumn(requirement.tableName, requirement.columnName);
                if (existing == null) {
                    statements.add(addColumnSql(requirement.tableName, requirement.columnName,
                            requirement.columnType, requirement.afterColumnName));
                    continue;
                }
                if (!existing.satisfies(requirement.columnType)) {
                    statements.add(modifyColumnSql(requirement.tableName, requirement.columnName, requirement.columnType));
                }
            }
        }
    }

    private void emitMissingTableStatements(List<String> statements,
                                            SchemaSnapshot snapshot,
                                            Map<String, TableRequirement> requirements,
                                            String ownerMainTable) {
        for (TableRequirement tableRequirement : requirements.values()) {
            if (!tableRequirement.belongsTo(ownerMainTable) || tableRequirement.isMainTable()) {
                continue;
            }
            if (!snapshot.hasTable(tableRequirement.tableName)) {
                statements.add(createTableSql(tableRequirement));
            }
        }
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
                                     String ownerMainTable,
                                     String tableName,
                                     String columnName,
                                     ColumnType columnType) {
        registerRequirement(requirements, ownerMainTable, tableName, columnName, columnType, false);
    }

    private void registerRequirement(Map<String, TableRequirement> requirements,
                                     String ownerMainTable,
                                     String tableName,
                                     String columnName,
                                     ColumnType columnType,
                                     boolean primaryKey) {
        registerRequirement(requirements, ownerMainTable, tableName, columnName, columnType, primaryKey, null);
    }

    private String registerRequirement(Map<String, TableRequirement> requirements,
                                       String ownerMainTable,
                                       String tableName,
                                       String columnName,
                                       ColumnType columnType,
                                       boolean primaryKey,
                                       String afterColumnName) {
        if (StringUtils.isBlank(tableName) || StringUtils.isBlank(columnName) || columnType == null) {
            return afterColumnName;
        }
        String normalizedTable = NameUtils.normalizeIdentifier(tableName);
        TableRequirement tableRequirement = requirements.get(normalizedTable);
        if (tableRequirement == null) {
            tableRequirement = new TableRequirement(tableName, ownerMainTable);
            requirements.put(normalizedTable, tableRequirement);
        }
        tableRequirement.register(columnName, columnType, primaryKey, afterColumnName);
        return columnName;
    }

    private ColumnType cipherType(ColumnMetadata sourceColumn) {
        int sourceLength = sourceColumn.resolveCharacterLength(sizingOptions.getFallbackCharacterLength());
        // 这里按“单字符最坏 UTF-8 字节数”估算明文尺寸，而不是直接使用 varchar 长度；
        // 中文、emoji 等多字节字符如果仍按字符数线性放大会低估密文列长度。
        long plainBytes = (long) sourceLength * sizingOptions.getCipherColumnMaxBytesPerChar();
        // 当前默认算法输出为 Base64([IV][ciphertext+tag])，因此需要把认证加密的固定开销一并计入。
        long payloadBytes = plainBytes + sizingOptions.getCipherColumnAuthenticatedPayloadOverheadBytes();
        // Base64 长度上取整到 4 的倍数；这里使用纯算术而非浮点，避免边界长度在不同 JVM 上出现偏差。
        long encodedLength = ((payloadBytes + 2) / 3) * 4;
        long boundedLength = Math.max(sizingOptions.getCipherColumnMinLength(), encodedLength);
        if (boundedLength > sizingOptions.getCipherColumnMaxVarcharLength()) {
            // 超过 varchar 安全阈值后直接退化为 text/clob，避免生成“语法合法但实际建表失败”的 DDL。
            return ColumnType.largeCharacter(dialect);
        }
        int length = (int) boundedLength;
        return ColumnType.variableCharacter(length, dialect);
    }

    private ColumnType hashType() {
        return ColumnType.variableCharacter(sizingOptions.getHashColumnLength(), dialect);
    }

    private ColumnType likeType(ColumnMetadata sourceColumn) {
        return sourceColumn.toComparableCharacterType(dialect, sizingOptions.getFallbackCharacterLength());
    }

    private ColumnType backupType(ColumnMetadata sourceColumn) {
        // 备份列需要完整保留迁移前原值，因此长度至少要满足当前源列声明长度；
        // 不能像 hash/ref 一样降维，否则独立表覆盖主列时会把较长明文截断。
        return sourceColumn.toComparableType(dialect, sizingOptions.getFallbackCharacterLength());
    }

    private ColumnType referenceIdType() {
        return ColumnType.variableCharacter(sizingOptions.getReferenceIdColumnLength(), dialect);
    }

    private SchemaSnapshot loadSnapshot() {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            Map<String, TableMetadata> tables = new LinkedHashMap<>();
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

    private String addColumnSql(String tableName,
                                String columnName,
                                ColumnType columnType,
                                String afterColumnName) {
        String quotedTable = dialect.quote(tableName);
        String quotedColumn = dialect.quote(columnName);
        switch (dialect) {
            case ORACLE12:
            case DM:
                return "alter table " + quotedTable + " add (" + quotedColumn + " " + columnType.renderedType + ")";
            default:
                StringBuilder sql = new StringBuilder("alter table ")
                        .append(quotedTable)
                        .append(" add column ")
                        .append(quotedColumn)
                        .append(' ')
                        .append(columnType.renderedType);
                if (supportsColumnPlacement() && StringUtils.isNotBlank(afterColumnName)) {
                    sql.append(" after ").append(dialect.quote(afterColumnName));
                }
                return sql.toString();
        }
    }

    private boolean supportsColumnPlacement() {
        return dialect == SqlDialect.MYSQL || dialect == SqlDialect.OCEANBASE;
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
        private int cipherColumnMinLength = 64;
        private int cipherColumnMaxBytesPerChar = 4;
        private int cipherColumnAuthenticatedPayloadOverheadBytes = 28;
        private int cipherColumnMaxVarcharLength = 4000;
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
         * 返回密文列最小长度。
         *
         * @return 密文列最小长度
         */
        public int getCipherColumnMinLength() {
            return cipherColumnMinLength;
        }

        /**
         * 设置密文列最小长度。
         *
         * @param cipherColumnMinLength 密文列最小长度，必须大于 0
         */
        public void setCipherColumnMinLength(int cipherColumnMinLength) {
            this.cipherColumnMinLength = positive(cipherColumnMinLength, "cipherColumnMinLength");
        }

        /**
         * 返回密文长度估算使用的单字符最大 UTF-8 字节数。
         *
         * @return 单字符最大字节数
         */
        public int getCipherColumnMaxBytesPerChar() {
            return cipherColumnMaxBytesPerChar;
        }

        /**
         * 设置密文长度估算使用的单字符最大 UTF-8 字节数。
         *
         * <p>默认值 {@code 4} 可覆盖中文、英文、数字和 UTF8MB4 字符。若字段明确只存手机号、
         * 身份证、银行卡等 ASCII 内容，可设置为 {@code 1} 以生成更紧凑的 DDL。</p>
         *
         * @param cipherColumnMaxBytesPerChar 单字符最大字节数，必须大于 0
         */
        public void setCipherColumnMaxBytesPerChar(int cipherColumnMaxBytesPerChar) {
            this.cipherColumnMaxBytesPerChar = positive(cipherColumnMaxBytesPerChar, "cipherColumnMaxBytesPerChar");
        }

        /**
         * 返回认证加密载荷固定开销字节数。
         *
         * @return 固定开销字节数
         */
        public int getCipherColumnAuthenticatedPayloadOverheadBytes() {
            return cipherColumnAuthenticatedPayloadOverheadBytes;
        }

        /**
         * 设置认证加密载荷固定开销字节数。
         *
         * <p>默认值 {@code 28} 对应当前 SM4/AES-GCM 输出中的 {@code 12} 字节 IV
         * 和 {@code 16} 字节认证标签。</p>
         *
         * @param cipherColumnAuthenticatedPayloadOverheadBytes 固定开销字节数，必须大于 0
         */
        public void setCipherColumnAuthenticatedPayloadOverheadBytes(
                int cipherColumnAuthenticatedPayloadOverheadBytes) {
            this.cipherColumnAuthenticatedPayloadOverheadBytes =
                    positive(cipherColumnAuthenticatedPayloadOverheadBytes,
                            "cipherColumnAuthenticatedPayloadOverheadBytes");
        }

        /**
         * 返回密文列使用 {@code varchar/varchar2} 的最大长度。
         *
         * @return 可变字符列最大长度
         */
        public int getCipherColumnMaxVarcharLength() {
            return cipherColumnMaxVarcharLength;
        }

        /**
         * 设置密文列使用 {@code varchar/varchar2} 的最大长度。
         *
         * <p>超过该长度时 DDL 会使用当前方言的大文本类型，例如 MySQL {@code text}
         * 或 Oracle {@code clob}。</p>
         *
         * @param cipherColumnMaxVarcharLength 可变字符列最大长度，必须大于 0
         */
        public void setCipherColumnMaxVarcharLength(int cipherColumnMaxVarcharLength) {
            this.cipherColumnMaxVarcharLength =
                    positive(cipherColumnMaxVarcharLength, "cipherColumnMaxVarcharLength");
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
        private final Map<String, ColumnMetadata> columns = new LinkedHashMap<>();

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
            if ("text".equals(targetType.family)) {
                return isLargeCharacterType();
            }
            if (!typeFamily().equals(targetType.family)) {
                return false;
            }
            if (targetType.length != null && columnSize > 0 && columnSize < targetType.length) {
                return false;
            }
            return targetType.scale == null || decimalDigits >= targetType.scale;
        }

        private boolean isLargeCharacterType() {
            String normalized = normalizeTypeName(typeName);
            return normalized.contains("text") || normalized.contains("clob")
                    || "string".equals(normalized);
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

        private static ColumnType largeCharacter(SqlDialect dialect) {
            return new ColumnType("text", largeCharacterTypeName(dialect), null, null);
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

        private static String largeCharacterTypeName(SqlDialect dialect) {
            switch (dialect) {
                case ORACLE12:
                case DM:
                    return "clob";
                case CLICKHOUSE:
                    return "String";
                default:
                    return "text";
            }
        }
    }

    private static final class ColumnRequirement {

        private final String tableName;
        private final String columnName;
        private final ColumnType columnType;
        private final String afterColumnName;

        private ColumnRequirement(String tableName,
                                  String columnName,
                                  ColumnType columnType,
                                  String afterColumnName) {
            this.tableName = tableName;
            this.columnName = columnName;
            this.columnType = columnType;
            this.afterColumnName = afterColumnName;
        }

        private ColumnRequirement merge(ColumnType incoming, String incomingAfterColumnName) {
            if (isTextVarcharCompatible(columnType, incoming)) {
                ColumnType mergedType = "text".equals(incoming.family) ? incoming : columnType;
                return new ColumnRequirement(tableName, columnName, mergedType,
                        mergeAfterColumnName(afterColumnName, incomingAfterColumnName));
            }
            if (!columnType.family.equals(incoming.family)) {
                throw new MigrationDefinitionException(MigrationErrorCode.DEFINITION_INVALID,
                        "Conflicting schema requirement for column: " + tableName + "." + columnName);
            }
            ColumnType mergedType = columnType;
            if (columnType.length == null || incoming.length == null) {
                return new ColumnRequirement(tableName, columnName, mergedType,
                        mergeAfterColumnName(afterColumnName, incomingAfterColumnName));
            }
            if (incoming.length > columnType.length) {
                mergedType = incoming;
            }
            return new ColumnRequirement(tableName, columnName, mergedType,
                    mergeAfterColumnName(afterColumnName, incomingAfterColumnName));
        }

        private boolean isTextVarcharCompatible(ColumnType current, ColumnType incoming) {
            return ("text".equals(current.family) && "varchar".equals(incoming.family))
                    || ("varchar".equals(current.family) && "text".equals(incoming.family));
        }

        private String mergeAfterColumnName(String currentAfterColumnName, String incomingAfterColumnName) {
            return StringUtils.isNotBlank(currentAfterColumnName) ? currentAfterColumnName : incomingAfterColumnName;
        }
    }

    private static final class TableRequirement {

        private final String tableName;
        private final String ownerMainTable;
        private final LinkedHashMap<String, ColumnRequirement> columns =
                new LinkedHashMap<String, ColumnRequirement>();
        private final List<String> primaryKeyColumns = new ArrayList<>();

        private TableRequirement(String tableName, String ownerMainTable) {
            this.tableName = tableName;
            this.ownerMainTable = ownerMainTable;
        }

        private void register(String columnName,
                              ColumnType columnType,
                              boolean primaryKey,
                              String afterColumnName) {
            String normalizedColumn = NameUtils.normalizeIdentifier(columnName);
            ColumnRequirement existing = columns.get(normalizedColumn);
            if (existing == null) {
                columns.put(normalizedColumn, new ColumnRequirement(tableName, columnName, columnType, afterColumnName));
            } else {
                columns.put(normalizedColumn, existing.merge(columnType, afterColumnName));
            }
            if (primaryKey && !primaryKeyColumns.contains(normalizedColumn)) {
                primaryKeyColumns.add(normalizedColumn);
            }
        }

        private boolean belongsTo(String mainTableName) {
            return NameUtils.normalizeIdentifier(ownerMainTable)
                    .equals(NameUtils.normalizeIdentifier(mainTableName));
        }

        private boolean isMainTable() {
            return NameUtils.normalizeIdentifier(tableName)
                    .equals(NameUtils.normalizeIdentifier(ownerMainTable));
        }
    }
}
