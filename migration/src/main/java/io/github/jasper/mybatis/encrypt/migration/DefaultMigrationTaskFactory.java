package io.github.jasper.mybatis.encrypt.migration;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;

import javax.sql.DataSource;
import java.util.List;
import java.util.function.Consumer;

/**
 * Default task factory backed by the standalone JDBC migration stack.
 */
public class DefaultMigrationTaskFactory implements MigrationTaskFactory {

    private final DataSource dataSource;
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
        this.dataSource = dataSource;
        this.metadataRegistry = metadataRegistry;
        this.algorithmRegistry = algorithmRegistry;
        this.properties = properties;
        this.stateStore = stateStore;
        this.confirmationPolicy = confirmationPolicy;
    }

    @Override
    public MigrationTask create(EntityMigrationDefinition definition) {
        return JdbcMigrationTasks.create(dataSource, definition, metadataRegistry, algorithmRegistry, properties,
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

    private EntityMigrationDefinition configure(EntityMigrationDefinition.Builder builder,
                                                Consumer<EntityMigrationDefinition.Builder> builderCustomizer) {
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
}
