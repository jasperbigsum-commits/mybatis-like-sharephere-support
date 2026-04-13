package io.github.jasper.mybatis.encrypt.migration;

import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptTableRule;
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
        EncryptTableRule tableRule = metadataRegistry.findByEntity(definition.getEntityType())
                .orElseThrow(() -> new MigrationException("Missing registered entity encryption rule: "
                        + definition.getEntityType().getName()));
        String normalizedTable = tableRule.getTableName();
        List<EntityMigrationColumnPlan> columnPlans = new ArrayList<EntityMigrationColumnPlan>();
        for (EncryptColumnRule columnRule : tableRule.getColumnRules()) {
            if (StringUtils.isNotBlank(columnRule.table())
                    && !normalizedTable.equals(NameUtils.normalizeIdentifier(columnRule.table()))) {
                throw new MigrationException("Migration only supports registered entity rules and ignores DTO fields: "
                        + definition.getEntityType().getName());
            }
            if (!definition.getIncludedProperties().isEmpty()
                    && !definition.getIncludedProperties().contains(columnRule.property())) {
                continue;
            }
            columnPlans.add(new EntityMigrationColumnPlan(
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
                    columnRule.storageIdColumn()
            ));
        }
        if (columnPlans.isEmpty()) {
            throw new MigrationException("No encrypt fields available for entity migration: "
                    + definition.getEntityType().getName());
        }
        return new EntityMigrationPlan(definition.getEntityType(), normalizedTable, definition.getIdColumn(),
                definition.getBatchSize(), definition.isVerifyAfterWrite(), columnPlans);
    }
}
