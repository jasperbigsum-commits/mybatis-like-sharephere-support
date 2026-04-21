package io.github.jasper.mybatis.encrypt.migration.risk;

import io.github.jasper.mybatis.encrypt.migration.EntityMigrationColumnPlan;
import io.github.jasper.mybatis.encrypt.migration.EntityMigrationPlan;
import io.github.jasper.mybatis.encrypt.migration.MigrationRiskEntry;
import io.github.jasper.mybatis.encrypt.migration.MigrationRiskManifest;
import io.github.jasper.mybatis.encrypt.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Expands a migration plan into concrete table/column mutations.
 */
public class MigrationRiskManifestFactory {

    /**
     * 创建任务元数工厂创建方法
     * @param plan 实例迁移计划
     * @return 迁移元数据
     */
    public MigrationRiskManifest create(EntityMigrationPlan plan) {
        Set<MigrationRiskEntry> entries = new LinkedHashSet<>();
        for (EntityMigrationColumnPlan columnPlan : plan.getColumnPlans()) {
            if (columnPlan.isStoredInSeparateTable()) {
                entries.add(new MigrationRiskEntry("UPDATE", plan.getTableName(), columnPlan.getSourceColumn()));
                if (columnPlan.shouldWriteBackup()) {
                    entries.add(new MigrationRiskEntry("UPDATE", plan.getTableName(), columnPlan.getBackupColumn()));
                }
                entries.add(new MigrationRiskEntry("INSERT", columnPlan.getStorageTable(), columnPlan.getStorageIdColumn()));
                entries.add(new MigrationRiskEntry("INSERT", columnPlan.getStorageTable(), columnPlan.getStorageColumn()));
                if (StringUtils.isNotBlank(columnPlan.getAssistedQueryColumn())) {
                    entries.add(new MigrationRiskEntry("INSERT", columnPlan.getStorageTable(),
                            columnPlan.getAssistedQueryColumn()));
                }
                if (StringUtils.isNotBlank(columnPlan.getLikeQueryColumn())) {
                    entries.add(new MigrationRiskEntry("INSERT", columnPlan.getStorageTable(),
                            columnPlan.getLikeQueryColumn()));
                }
                if (StringUtils.isNotBlank(columnPlan.getMaskedColumn())) {
                    entries.add(new MigrationRiskEntry("INSERT", columnPlan.getStorageTable(),
                            columnPlan.getMaskedColumn()));
                }
                continue;
            }
            if (columnPlan.shouldWriteBackup()) {
                entries.add(new MigrationRiskEntry("UPDATE", plan.getTableName(), columnPlan.getBackupColumn()));
            }
            entries.add(new MigrationRiskEntry("UPDATE", plan.getTableName(), columnPlan.getStorageColumn()));
            if (StringUtils.isNotBlank(columnPlan.getAssistedQueryColumn())) {
                entries.add(new MigrationRiskEntry("UPDATE", plan.getTableName(), columnPlan.getAssistedQueryColumn()));
            }
            if (StringUtils.isNotBlank(columnPlan.getLikeQueryColumn())) {
                entries.add(new MigrationRiskEntry("UPDATE", plan.getTableName(), columnPlan.getLikeQueryColumn()));
            }
            if (StringUtils.isNotBlank(columnPlan.getMaskedColumn())) {
                entries.add(new MigrationRiskEntry("UPDATE", plan.getTableName(), columnPlan.getMaskedColumn()));
            }
        }
        return new MigrationRiskManifest(plan.getDataSourceName(), plan.getEntityName(), plan.getTableName(), plan.getCursorColumns(),
                new ArrayList<>(entries));
    }
}
