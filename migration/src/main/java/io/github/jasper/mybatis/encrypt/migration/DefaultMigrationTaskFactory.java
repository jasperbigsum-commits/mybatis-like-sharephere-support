package io.github.jasper.mybatis.encrypt.migration;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Default task factory backed by the standalone JDBC migration stack.
 */
public class DefaultMigrationTaskFactory implements MigrationTaskFactory {

    private final DataSource dataSource;
    private final String dataSourceName;
    private final EncryptMetadataRegistry metadataRegistry;
    private final AlgorithmRegistry algorithmRegistry;
    private final DatabaseEncryptionProperties properties;
    private final MigrationStateStore stateStore;
    private final MigrationConfirmationPolicy confirmationPolicy;

    /**
     * Create one factory with application-managed infrastructure dependencies.
     *
     * @param dataSource JDBC data source
     * @param metadataRegistry metadata registry
     * @param algorithmRegistry algorithm registry
     * @param properties encryption properties
     * @param stateStore state store
     * @param confirmationPolicy confirmation policy
     */
    public DefaultMigrationTaskFactory(DataSource dataSource,
                                       EncryptMetadataRegistry metadataRegistry,
                                       AlgorithmRegistry algorithmRegistry,
                                       DatabaseEncryptionProperties properties,
                                       MigrationStateStore stateStore,
                                       MigrationConfirmationPolicy confirmationPolicy) {
        this(dataSource, null, metadataRegistry, algorithmRegistry, properties, stateStore, confirmationPolicy);
    }

    /**
     * Create one factory with application-managed infrastructure dependencies.
     *
     * @param dataSource JDBC data source
     * @param dataSourceName JDBC 数据源名称
     * @param metadataRegistry metadata registry
     * @param algorithmRegistry algorithm registry
     * @param properties encryption properties
     * @param stateStore state store
     * @param confirmationPolicy confirmation policy
     */
    public DefaultMigrationTaskFactory(DataSource dataSource,
                                       String dataSourceName,
                                       EncryptMetadataRegistry metadataRegistry,
                                       AlgorithmRegistry algorithmRegistry,
                                       DatabaseEncryptionProperties properties,
                                       MigrationStateStore stateStore,
                                       MigrationConfirmationPolicy confirmationPolicy) {
        this.dataSource = dataSource;
        this.dataSourceName = dataSourceName;
        this.metadataRegistry = metadataRegistry;
        this.algorithmRegistry = algorithmRegistry;
        this.properties = properties;
        this.stateStore = stateStore;
        this.confirmationPolicy = confirmationPolicy;
    }

    @Override
    public MigrationTask create(EntityMigrationDefinition definition) {
        return JdbcMigrationTasks.create(dataSource, dataSourceName, definition, metadataRegistry, algorithmRegistry, properties,
                stateStore, confirmationPolicy);
    }

    @Override
    public MigrationTask createForEntity(Class<?> entityType, List<String> cursorColumns) {
        return createForEntity(entityType, cursorColumns, null);
    }

    @Override
    public MigrationTask createForEntity(Class<?> entityType,
                                         List<String> cursorColumns,
                                         Consumer<EntityMigrationDefinition.Builder> builderCustomizer) {
        return create(configure(builderForEntity(entityType, cursorColumns), builderCustomizer));
    }

    @Override
    public MigrationTask createForTable(String tableName, List<String> cursorColumns) {
        return createForTable(tableName, cursorColumns, null);
    }

    @Override
    public MigrationTask createForTable(String tableName,
                                        List<String> cursorColumns,
                                        Consumer<EntityMigrationDefinition.Builder> builderCustomizer) {
        return create(configure(builderForTable(tableName, cursorColumns), builderCustomizer));
    }

    @Override
    public MigrationReport executeForEntity(Class<?> entityType, List<String> cursorColumns) {
        return createForEntity(entityType, cursorColumns).execute();
    }

    @Override
    public MigrationReport executeForEntity(Class<?> entityType,
                                            List<String> cursorColumns,
                                            Consumer<EntityMigrationDefinition.Builder> builderCustomizer) {
        return createForEntity(entityType, cursorColumns, builderCustomizer).execute();
    }

    @Override
    public MigrationReport executeForTable(String tableName, List<String> cursorColumns) {
        return createForTable(tableName, cursorColumns).execute();
    }

    @Override
    public MigrationReport executeForTable(String tableName,
                                           List<String> cursorColumns,
                                           Consumer<EntityMigrationDefinition.Builder> builderCustomizer) {
        return createForTable(tableName, cursorColumns, builderCustomizer).execute();
    }

    @Override
    public MigrationTask createForEntity(Class<?> entityType) {
        return createForEntity(entityType, (Consumer<EntityMigrationDefinition.Builder>) null);
    }

    @Override
    public MigrationTask createForTable(String tableName) {
        return createForTable(tableName, (Consumer<EntityMigrationDefinition.Builder>) null);
    }

    @Override
    public MigrationTask createForEntity(Class<?> entityType,
                                         Consumer<EntityMigrationDefinition.Builder> builderCustomizer) {
        return createForEntity(entityType, defaultCursorColumns(), builderCustomizer);
    }

    @Override
    public MigrationTask createForTable(String tableName,
                                        Consumer<EntityMigrationDefinition.Builder> builderCustomizer) {
        return createForTable(tableName, defaultCursorColumns(), builderCustomizer);
    }

    @Override
    public List<MigrationTask> createAllRegisteredTables() {
        return createAllRegisteredTables(null);
    }

    @Override
    public List<MigrationTask> createAllRegisteredTables(Consumer<EntityMigrationDefinition.Builder> builderCustomizer) {
        List<MigrationTask> tasks = new ArrayList<MigrationTask>();
        for (String tableName : registeredTableNames()) {
            tasks.add(createForTable(tableName, builderCustomizer));
        }
        return tasks;
    }

    @Override
    public MigrationReport executeForEntity(Class<?> entityType) {
        return executeForEntity(entityType, (Consumer<EntityMigrationDefinition.Builder>) null);
    }

    @Override
    public MigrationReport executeForEntity(Class<?> entityType,
                                            Consumer<EntityMigrationDefinition.Builder> builderCustomizer) {
        return createForEntity(entityType, builderCustomizer).execute();
    }

    @Override
    public MigrationReport executeForTable(String tableName) {
        return executeForTable(tableName, (Consumer<EntityMigrationDefinition.Builder>) null);
    }

    @Override
    public MigrationReport executeForTable(String tableName,
                                           Consumer<EntityMigrationDefinition.Builder> builderCustomizer) {
        return createForTable(tableName, builderCustomizer).execute();
    }

    @Override
    public List<MigrationReport> executeAllRegisteredTables() {
        return executeAllRegisteredTables(null);
    }

    @Override
    public List<MigrationReport> executeAllRegisteredTables(Consumer<EntityMigrationDefinition.Builder> builderCustomizer) {
        List<MigrationReport> reports = new ArrayList<MigrationReport>();
        for (MigrationTask task : createAllRegisteredTables(builderCustomizer)) {
            reports.add(task.execute());
        }
        return reports;
    }

    private EntityMigrationDefinition configure(EntityMigrationDefinition.Builder builder,
                                                Consumer<EntityMigrationDefinition.Builder> builderCustomizer) {
        DatabaseEncryptionProperties.MigrationProperties migrationProperties = properties.getMigration();
        if (migrationProperties != null) {
            builder.batchSize(migrationProperties.getBatchSize());
            builder.verifyAfterWrite(migrationProperties.isVerifyAfterWrite());
        }
        if (builderCustomizer != null) {
            builderCustomizer.accept(builder);
        }
        return builder.build();
    }

    private EntityMigrationDefinition.Builder builderForEntity(Class<?> entityType, List<String> cursorColumns) {
        if (cursorColumns == null || cursorColumns.isEmpty()) {
            throw new IllegalArgumentException("cursorColumns must not be empty");
        }
        return EntityMigrationDefinition.builder(entityType, cursorColumns.get(0),
                cursorColumns.size() <= 1 ? new String[0] : cursorColumns.subList(1, cursorColumns.size()).toArray(new String[0]));
    }

    private EntityMigrationDefinition.Builder builderForTable(String tableName, List<String> cursorColumns) {
        if (cursorColumns == null || cursorColumns.isEmpty()) {
            throw new IllegalArgumentException("cursorColumns must not be empty");
        }
        return EntityMigrationDefinition.builder(tableName, cursorColumns.get(0),
                cursorColumns.size() <= 1 ? new String[0] : cursorColumns.subList(1, cursorColumns.size()).toArray(new String[0]));
    }

    private List<String> defaultCursorColumns() {
        DatabaseEncryptionProperties.MigrationProperties migrationProperties = properties.getMigration();
        List<String> cursorColumns = migrationProperties == null ? null : migrationProperties.getDefaultCursorColumns();
        if (cursorColumns == null || cursorColumns.isEmpty()) {
            throw new IllegalArgumentException("migration.defaultCursorColumns must not be empty");
        }
        return cursorColumns;
    }

    private Set<String> registeredTableNames() {
        return new LinkedHashSet<String>(metadataRegistry.getRegisteredTableNames());
    }
}
