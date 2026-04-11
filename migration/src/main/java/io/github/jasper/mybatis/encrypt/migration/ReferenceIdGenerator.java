package io.github.jasper.mybatis.encrypt.migration;

/**
 * Generates reference ids for separate-table migration rows.
 */
public interface ReferenceIdGenerator {

    /**
     * Generate the next reference id.
     *
     * @param plan current field migration plan
     * @param record source row
     * @return generated reference id
     */
    Object nextReferenceId(EntityMigrationColumnPlan plan, MigrationRecord record);
}
