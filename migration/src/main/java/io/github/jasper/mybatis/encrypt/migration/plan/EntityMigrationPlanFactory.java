package io.github.jasper.mybatis.encrypt.migration.plan;

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
import java.util.List;

/**
 * Builds migration plans from registered entity metadata and rejects DTO-style multi-table rules.
 */
public class EntityMigrationPlanFactory {

    private final EncryptMetadataRegistry metadataRegistry;

    /**
     * 实体迁移计划创建工厂
     * @param metadataRegistry 元注册信息
     */
    public EntityMigrationPlanFactory(EncryptMetadataRegistry metadataRegistry) {
        this.metadataRegistry = metadataRegistry;
    }

    /**
     * 创建方法
     * @param definition 定义信息
     * @return entityMigrationPlan 实例
     */
    public EntityMigrationPlan create(EntityMigrationDefinition definition) {
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
        return new EntityMigrationPlan(resolved.entityType(), resolved.entityName(), normalizedTable,
                definition.getCursorColumns(),
                definition.getBatchSize(), definition.isVerifyAfterWrite(), columnPlans);
    }

    private List<EntityMigrationColumnPlan> collectColumnPlans(ResolvedDefinition resolved,
                                                               String normalizedTable,
                                                               MigrationFieldSelectorResolver selectorResolver) {
        List<EntityMigrationColumnPlan> columnPlans = new ArrayList<>();
        for (EncryptColumnRule columnRule : resolved.tableRule().getColumnRules()) {
            rejectDtoStyleRuleIfNecessary(resolved, normalizedTable, columnRule);
            if (!selectorResolver.includes(columnRule)) {
                continue;
            }
            String backupColumn = selectorResolver.resolveBackupColumn(columnRule);
            validateBackupColumn(columnRule, backupColumn);
            columnPlans.add(toColumnPlan(columnRule, backupColumn));
        }
        return columnPlans;
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
            return new ResolvedDefinition(entityType, entityType.getName(), tableRule);
        }
        String tableName = definition.getTableName();
        EncryptTableRule tableRule = metadataRegistry.findByTable(tableName)
                .orElseThrow(() -> new MigrationDefinitionException(MigrationErrorCode.METADATA_RULE_MISSING,
                        "Missing registered table encryption rule: " + tableName));
        return new ResolvedDefinition(null, tableRule.getTableName(), tableRule);
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
}
