package io.github.jasper.mybatis.encrypt.migration.plan;

import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptTableRule;
import io.github.jasper.mybatis.encrypt.migration.EntityMigrationColumnPlan;
import io.github.jasper.mybatis.encrypt.migration.EntityMigrationDefinition;
import io.github.jasper.mybatis.encrypt.migration.EntityMigrationPlan;
import io.github.jasper.mybatis.encrypt.migration.MigrationDefinitionException;
import io.github.jasper.mybatis.encrypt.migration.MigrationErrorCode;
import io.github.jasper.mybatis.encrypt.util.NameUtils;
import io.github.jasper.mybatis.encrypt.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds migration plans from registered entity metadata and rejects DTO-style multi-table rules.
 */
public class EntityMigrationPlanFactory {

    private final EncryptMetadataRegistry metadataRegistry;
    private final DatabaseEncryptionProperties properties;

    /**
     * 实体迁移计划创建工厂
     * @param metadataRegistry 元注册信息
     */
    public EntityMigrationPlanFactory(EncryptMetadataRegistry metadataRegistry) {
        this(metadataRegistry, new DatabaseEncryptionProperties());
    }

    /**
     * 实体迁移计划创建工厂
     * @param metadataRegistry 元注册信息
     * @param properties 外部配置
     */
    public EntityMigrationPlanFactory(EncryptMetadataRegistry metadataRegistry,
                                      DatabaseEncryptionProperties properties) {
        this.metadataRegistry = metadataRegistry;
        this.properties = properties == null ? new DatabaseEncryptionProperties() : properties;
    }

    /**
     * 创建方法
     * @param definition 定义信息
     * @return entityMigrationPlan 实例
     */
    public EntityMigrationPlan create(EntityMigrationDefinition definition) {
        return create(definition, null);
    }

    /**
     * 创建方法
     * @param definition 定义信息
     * @param dataSourceName 数据源名称
     * @return entityMigrationPlan 实例
     */
    public EntityMigrationPlan create(EntityMigrationDefinition definition, String dataSourceName) {
        ResolvedDefinition resolved = resolveDefinition(definition);
        String normalizedTable = resolved.tableRule().getTableName();
        MigrationFieldSelectorResolver selectorResolver = new MigrationFieldSelectorResolver(definition);
        List<EntityMigrationColumnPlan> columnPlans = collectColumnPlans(resolved, normalizedTable, selectorResolver);
        selectorResolver.assertResolved();
        if (columnPlans.isEmpty()) {
            throw new MigrationDefinitionException(MigrationErrorCode.DEFINITION_INVALID,
                    "No encrypt fields available for migration target: "
                    + resolved.entityName());
        }
        validateCursorColumns(definition.getCursorColumns(), columnPlans);
        return new EntityMigrationPlan(dataSourceName, resolved.entityType(), resolved.entityName(), normalizedTable,
                definition.getCursorColumns(),
                definition.getBatchSize(), definition.isVerifyAfterWrite(), columnPlans);
    }

    private List<EntityMigrationColumnPlan> collectColumnPlans(ResolvedDefinition resolved,
                                                               String normalizedTable,
                                                               MigrationFieldSelectorResolver selectorResolver) {
        Map<MappingKey, EntityMigrationColumnPlan> deduplicatedPlans =
                new LinkedHashMap<MappingKey, EntityMigrationColumnPlan>();
        for (EncryptColumnRule columnRule : resolved.tableRule().getColumnRules()) {
            rejectDtoStyleRuleIfNecessary(resolved, normalizedTable, columnRule);
            if (!selectorResolver.includes(columnRule)) {
                continue;
            }
            String backupColumn = selectorResolver.resolveBackupColumn(columnRule);
            if (StringUtils.isBlank(backupColumn)) {
                backupColumn = properties.resolveMigrationBackupColumn(
                        normalizedTable, columnRule.property(), columnRule.column());
            }
            validateBackupColumn(columnRule, backupColumn);
            EntityMigrationColumnPlan columnPlan = toColumnPlan(columnRule, backupColumn);
            MappingKey mappingKey = MappingKey.of(columnPlan);
            EntityMigrationColumnPlan existing = deduplicatedPlans.get(mappingKey);
            if (existing == null) {
                deduplicatedPlans.put(mappingKey, columnPlan);
                continue;
            }
            if (!sameBackupColumn(existing.getBackupColumn(), columnPlan.getBackupColumn())) {
                throw new MigrationDefinitionException(MigrationErrorCode.DEFINITION_INVALID,
                        "Duplicate migration mapping uses inconsistent backup column. first="
                                + existing.getProperty() + ", duplicate=" + columnPlan.getProperty());
            }
        }
        return new ArrayList<>(deduplicatedPlans.values());
    }

    private boolean sameBackupColumn(String first, String second) {
        return Objects.equals(NameUtils.normalizeIdentifier(first), NameUtils.normalizeIdentifier(second));
    }

    private void rejectDtoStyleRuleIfNecessary(ResolvedDefinition resolved,
                                               String normalizedTable,
                                               EncryptColumnRule columnRule) {
        if (resolved.entityType() != null
                && StringUtils.isNotBlank(columnRule.table())
                && !normalizedTable.equals(NameUtils.normalizeIdentifier(columnRule.table()))) {
            throw new MigrationDefinitionException(MigrationErrorCode.DEFINITION_INVALID,
                    "Migration only supports registered entity rules and ignores DTO fields: "
                    + resolved.entityName());
        }
    }

    private EntityMigrationColumnPlan toColumnPlan(EncryptColumnRule columnRule, String backupColumn) {
        return new EntityMigrationColumnPlan(
                columnRule.property(),
                columnRule.column(),
                columnRule.storageColumn(),
                columnRule.assistedQueryColumn(),
                columnRule.likeQueryColumn(),
                columnRule.cipherAlgorithm(),
                columnRule.assistedQueryAlgorithm(),
                columnRule.likeQueryAlgorithm(),
                columnRule.isStoredInSeparateTable(),
                columnRule.storageTable(),
                columnRule.storageIdColumn(),
                backupColumn
        );
    }

    private void validateBackupColumn(EncryptColumnRule columnRule, String backupColumn) {
        if (StringUtils.isBlank(backupColumn)) {
            return;
        }
        String normalizedBackup = NameUtils.normalizeIdentifier(backupColumn);
        if (normalizedBackup.equals(NameUtils.normalizeIdentifier(columnRule.column()))
                || normalizedBackup.equals(NameUtils.normalizeIdentifier(columnRule.storageColumn()))
                || normalizedBackup.equals(NameUtils.normalizeIdentifier(columnRule.assistedQueryColumn()))
                || normalizedBackup.equals(NameUtils.normalizeIdentifier(columnRule.likeQueryColumn()))) {
            throw new MigrationDefinitionException(MigrationErrorCode.BACKUP_COLUMN_CONFLICT,
                    "Backup column conflicts with migration target columns for property: "
                    + columnRule.property());
        }
    }

    private ResolvedDefinition resolveDefinition(EntityMigrationDefinition definition) {
        if (definition.getEntityType() != null) {
            Class<?> entityType = definition.getEntityType();
            EncryptTableRule tableRule = metadataRegistry.findByEntity(entityType)
                    .orElseThrow(() -> new MigrationDefinitionException(MigrationErrorCode.METADATA_RULE_MISSING,
                            "Missing registered entity encryption rule: " + entityType.getName()));
            assertNotExcluded(tableRule.getTableName());
            return new ResolvedDefinition(entityType, entityType.getName(), tableRule);
        }
        String tableName = definition.getTableName();
        EncryptTableRule tableRule = metadataRegistry.findByTable(tableName)
                .orElseThrow(() -> new MigrationDefinitionException(MigrationErrorCode.METADATA_RULE_MISSING,
                        "Missing registered table encryption rule: " + tableName));
        assertNotExcluded(tableRule.getTableName());
        return new ResolvedDefinition(null, tableRule.getTableName(), tableRule);
    }

    private void assertNotExcluded(String tableName) {
        if (properties.isMigrationTableExcluded(tableName)) {
            throw new MigrationDefinitionException(MigrationErrorCode.TABLE_EXCLUDED,
                    "Migration target table is excluded by global configuration: " + tableName);
        }
    }

    private void validateCursorColumns(List<String> cursorColumns, List<EntityMigrationColumnPlan> columnPlans) {
        for (String cursorColumn : cursorColumns) {
            for (EntityMigrationColumnPlan columnPlan : columnPlans) {
                if (columnPlan.mutatesMainTableColumn(cursorColumn)) {
                    throw new MigrationDefinitionException(MigrationErrorCode.CURSOR_COLUMN_MUTABLE,
                            "Migration cursor column will be mutated during migration: " + cursorColumn
                                    + ", property=" + columnPlan.getProperty());
                }
            }
        }
    }

    private static final class ResolvedDefinition {

        private final Class<?> entityType;
        private final String entityName;
        private final EncryptTableRule tableRule;

        private ResolvedDefinition(Class<?> entityType, String entityName, EncryptTableRule tableRule) {
            this.entityType = entityType;
            this.entityName = entityName;
            this.tableRule = tableRule;
        }

        private Class<?> entityType() {
            return entityType;
        }

        private String entityName() {
            return entityName;
        }

        private EncryptTableRule tableRule() {
            return tableRule;
        }
    }

    private static final class MappingKey {

        private final String sourceColumn;
        private final String storageColumn;
        private final String assistedQueryColumn;
        private final String likeQueryColumn;
        private final String cipherAlgorithm;
        private final String assistedQueryAlgorithm;
        private final String likeQueryAlgorithm;
        private final boolean storedInSeparateTable;
        private final String storageTable;
        private final String storageIdColumn;

        private MappingKey(String sourceColumn,
                           String storageColumn,
                           String assistedQueryColumn,
                           String likeQueryColumn,
                           String cipherAlgorithm,
                           String assistedQueryAlgorithm,
                           String likeQueryAlgorithm,
                           boolean storedInSeparateTable,
                           String storageTable,
                           String storageIdColumn) {
            this.sourceColumn = sourceColumn;
            this.storageColumn = storageColumn;
            this.assistedQueryColumn = assistedQueryColumn;
            this.likeQueryColumn = likeQueryColumn;
            this.cipherAlgorithm = cipherAlgorithm;
            this.assistedQueryAlgorithm = assistedQueryAlgorithm;
            this.likeQueryAlgorithm = likeQueryAlgorithm;
            this.storedInSeparateTable = storedInSeparateTable;
            this.storageTable = storageTable;
            this.storageIdColumn = storageIdColumn;
        }

        private static MappingKey of(EntityMigrationColumnPlan columnPlan) {
            return new MappingKey(
                    NameUtils.normalizeIdentifier(columnPlan.getSourceColumn()),
                    NameUtils.normalizeIdentifier(columnPlan.getStorageColumn()),
                    NameUtils.normalizeIdentifier(columnPlan.getAssistedQueryColumn()),
                    NameUtils.normalizeIdentifier(columnPlan.getLikeQueryColumn()),
                    columnPlan.getCipherAlgorithm(),
                    columnPlan.getAssistedQueryAlgorithm(),
                    columnPlan.getLikeQueryAlgorithm(),
                    columnPlan.isStoredInSeparateTable(),
                    NameUtils.normalizeIdentifier(columnPlan.getStorageTable()),
                    NameUtils.normalizeIdentifier(columnPlan.getStorageIdColumn())
            );
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof MappingKey)) {
                return false;
            }
            MappingKey that = (MappingKey) other;
            return storedInSeparateTable == that.storedInSeparateTable
                    && Objects.equals(sourceColumn, that.sourceColumn)
                    && Objects.equals(storageColumn, that.storageColumn)
                    && Objects.equals(assistedQueryColumn, that.assistedQueryColumn)
                    && Objects.equals(likeQueryColumn, that.likeQueryColumn)
                    && Objects.equals(cipherAlgorithm, that.cipherAlgorithm)
                    && Objects.equals(assistedQueryAlgorithm, that.assistedQueryAlgorithm)
                    && Objects.equals(likeQueryAlgorithm, that.likeQueryAlgorithm)
                    && Objects.equals(storageTable, that.storageTable)
                    && Objects.equals(storageIdColumn, that.storageIdColumn);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sourceColumn, storageColumn, assistedQueryColumn, likeQueryColumn,
                    cipherAlgorithm, assistedQueryAlgorithm, likeQueryAlgorithm,
                    storedInSeparateTable, storageTable, storageIdColumn);
        }
    }
}
