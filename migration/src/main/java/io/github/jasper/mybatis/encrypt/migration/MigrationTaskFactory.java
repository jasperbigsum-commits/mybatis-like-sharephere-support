package io.github.jasper.mybatis.encrypt.migration;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Convenience factory that hides infrastructure dependencies behind simple migration entry points.
 */
public interface MigrationTaskFactory {

    /**
     * Create one task from a fully prepared immutable definition.
     *
     * @param definition migration definition
     * @return executable migration task
     */
    MigrationTask create(EntityMigrationDefinition definition);

    /**
     * Create one task for a registered entity using ordered stable cursor columns.
     *
     * @param entityType registered entity type
     * @param cursorColumns ordered cursor columns in the main table
     * @return executable migration task
     */
    MigrationTask createForEntity(Class<?> entityType, List<String> cursorColumns);

    /**
     * Create one task for a registered entity using ordered stable cursor columns and one builder customizer.
     *
     * @param entityType registered entity type
     * @param cursorColumns ordered cursor columns in the main table
     * @param builderCustomizer optional builder customizer
     * @return executable migration task
     */
    MigrationTask createForEntity(Class<?> entityType,
                                  List<String> cursorColumns,
                                  Consumer<EntityMigrationDefinition.Builder> builderCustomizer);

    /**
     * Create one task for a registered table using ordered stable cursor columns.
     *
     * @param tableName physical table name
     * @param cursorColumns ordered cursor columns in the main table
     * @return executable migration task
     */
    MigrationTask createForTable(String tableName, List<String> cursorColumns);

    /**
     * Create one task for a registered table using ordered stable cursor columns and one builder customizer.
     *
     * @param tableName physical table name
     * @param cursorColumns ordered cursor columns in the main table
     * @param builderCustomizer optional builder customizer
     * @return executable migration task
     */
    MigrationTask createForTable(String tableName,
                                 List<String> cursorColumns,
                                 Consumer<EntityMigrationDefinition.Builder> builderCustomizer);

    /**
     * Create and execute one task for a registered entity.
     *
     * @param entityType registered entity type
     * @param cursorColumns ordered cursor columns in the main table
     * @return final migration report
     */
    MigrationReport executeForEntity(Class<?> entityType, List<String> cursorColumns);

    /**
     * Create and execute one task for a registered entity with one builder customizer.
     *
     * @param entityType registered entity type
     * @param cursorColumns ordered cursor columns in the main table
     * @param builderCustomizer optional builder customizer
     * @return final migration report
     */
    MigrationReport executeForEntity(Class<?> entityType,
                                     List<String> cursorColumns,
                                     Consumer<EntityMigrationDefinition.Builder> builderCustomizer);

    /**
     * Create and execute one task for a registered table.
     *
     * @param tableName physical table name
     * @param cursorColumns ordered cursor columns in the main table
     * @return final migration report
     */
    MigrationReport executeForTable(String tableName, List<String> cursorColumns);

    /**
     * Create and execute one task for a registered table with one builder customizer.
     *
     * @param tableName physical table name
     * @param cursorColumns ordered cursor columns in the main table
     * @param builderCustomizer optional builder customizer
     * @return final migration report
     */
    MigrationReport executeForTable(String tableName,
                                    List<String> cursorColumns,
                                    Consumer<EntityMigrationDefinition.Builder> builderCustomizer);

    /**
     * Create one task for a registered entity using globally configured default cursor columns.
     *
     * @param entityType registered entity type
     * @return executable migration task
     */
    MigrationTask createForEntity(Class<?> entityType);

    /**
     * Create one task for a registered table using globally configured default cursor columns.
     *
     * @param tableName physical table name
     * @return executable migration task
     */
    MigrationTask createForTable(String tableName);

    /**
     * Create one task for a registered entity using globally configured default cursor columns.
     *
     * @param entityType registered entity type
     * @param builderCustomizer optional builder customizer
     * @return executable migration task
     */
    MigrationTask createForEntity(Class<?> entityType, Consumer<EntityMigrationDefinition.Builder> builderCustomizer);

    /**
     * Create one task for a registered table using globally configured default cursor columns.
     *
     * @param tableName physical table name
     * @param builderCustomizer optional builder customizer
     * @return executable migration task
     */
    MigrationTask createForTable(String tableName, Consumer<EntityMigrationDefinition.Builder> builderCustomizer);

    /**
     * Create tasks for all registered physical tables using globally configured default cursor columns.
     *
     * @return executable migration tasks
     */
    List<MigrationTask> createAllRegisteredTables();

    /**
     * Create tasks for all registered physical tables using globally configured default cursor columns.
     *
     * @param builderCustomizer optional builder customizer applied to each task
     * @return executable migration tasks
     */
    List<MigrationTask> createAllRegisteredTables(Consumer<EntityMigrationDefinition.Builder> builderCustomizer);

    /**
     * Create and execute one task for a registered entity using globally configured default cursor columns.
     *
     * @param entityType registered entity type
     * @return final migration report
     */
    MigrationReport executeForEntity(Class<?> entityType);

    /**
     * Create and execute one task for a registered entity using globally configured default cursor columns.
     *
     * @param entityType registered entity type
     * @param builderCustomizer optional builder customizer
     * @return final migration report
     */
    MigrationReport executeForEntity(Class<?> entityType, Consumer<EntityMigrationDefinition.Builder> builderCustomizer);

    /**
     * Create and execute one task for a registered table using globally configured default cursor columns.
     *
     * @param tableName physical table name
     * @return final migration report
     */
    MigrationReport executeForTable(String tableName);

    /**
     * Create and execute one task for a registered table using globally configured default cursor columns.
     *
     * @param tableName physical table name
     * @param builderCustomizer optional builder customizer
     * @return final migration report
     */
    MigrationReport executeForTable(String tableName, Consumer<EntityMigrationDefinition.Builder> builderCustomizer);

    /**
     * Execute all registered physical tables using globally configured default cursor columns.
     *
     * @return final migration reports
     */
    List<MigrationReport> executeAllRegisteredTables();

    /**
     * Execute all registered physical tables using globally configured default cursor columns.
     *
     * @param builderCustomizer optional builder customizer applied to each task
     * @return final migration reports
     */
    List<MigrationReport> executeAllRegisteredTables(Consumer<EntityMigrationDefinition.Builder> builderCustomizer);

    /**
     * Create one task for a registered entity using one stable cursor column.
     *
     * @param entityType registered entity type
     * @param cursorColumn stable cursor column in the main table
     * @return executable migration task
     */
    default MigrationTask createForEntity(Class<?> entityType, String cursorColumn) {
        return createForEntity(entityType, Collections.singletonList(cursorColumn));
    }

    /**
     * Create one task for a registered entity using one stable cursor column and one builder customizer.
     *
     * @param entityType registered entity type
     * @param cursorColumn stable cursor column in the main table
     * @param builderCustomizer optional builder customizer
     * @return executable migration task
     */
    default MigrationTask createForEntity(Class<?> entityType,
                                          String cursorColumn,
                                          Consumer<EntityMigrationDefinition.Builder> builderCustomizer) {
        return createForEntity(entityType, Collections.singletonList(cursorColumn), builderCustomizer);
    }

    /**
     * Create one task for a registered table using one stable cursor column.
     *
     * @param tableName physical table name
     * @param cursorColumn stable cursor column in the main table
     * @return executable migration task
     */
    default MigrationTask createForTable(String tableName, String cursorColumn) {
        return createForTable(tableName, Collections.singletonList(cursorColumn));
    }

    /**
     * Create one task for a registered table using one stable cursor column and one builder customizer.
     *
     * @param tableName physical table name
     * @param cursorColumn stable cursor column in the main table
     * @param builderCustomizer optional builder customizer
     * @return executable migration task
     */
    default MigrationTask createForTable(String tableName,
                                         String cursorColumn,
                                         Consumer<EntityMigrationDefinition.Builder> builderCustomizer) {
        return createForTable(tableName, Collections.singletonList(cursorColumn), builderCustomizer);
    }

    /**
     * Create and execute one task for a registered entity.
     *
     * @param entityType registered entity type
     * @param cursorColumn stable cursor column in the main table
     * @return final migration report
     */
    default MigrationReport executeForEntity(Class<?> entityType, String cursorColumn) {
        return executeForEntity(entityType, Collections.singletonList(cursorColumn));
    }

    /**
     * Create and execute one task for a registered entity with one builder customizer.
     *
     * @param entityType registered entity type
     * @param cursorColumn stable cursor column in the main table
     * @param builderCustomizer optional builder customizer
     * @return final migration report
     */
    default MigrationReport executeForEntity(Class<?> entityType,
                                             String cursorColumn,
                                             Consumer<EntityMigrationDefinition.Builder> builderCustomizer) {
        return executeForEntity(entityType, Collections.singletonList(cursorColumn), builderCustomizer);
    }

    /**
     * Create and execute one task for a registered table.
     *
     * @param tableName physical table name
     * @param cursorColumn stable cursor column in the main table
     * @return final migration report
     */
    default MigrationReport executeForTable(String tableName, String cursorColumn) {
        return executeForTable(tableName, Collections.singletonList(cursorColumn));
    }

    /**
     * Create and execute one task for a registered table with one builder customizer.
     *
     * @param tableName physical table name
     * @param cursorColumn stable cursor column in the main table
     * @param builderCustomizer optional builder customizer
     * @return final migration report
     */
    default MigrationReport executeForTable(String tableName,
                                            String cursorColumn,
                                            Consumer<EntityMigrationDefinition.Builder> builderCustomizer) {
        return executeForTable(tableName, Collections.singletonList(cursorColumn), builderCustomizer);
    }
}
