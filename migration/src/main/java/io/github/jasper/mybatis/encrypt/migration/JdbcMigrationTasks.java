package io.github.jasper.mybatis.encrypt.migration;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;

import javax.sql.DataSource;

/**
 * Convenience factory for the default standalone JDBC migration stack.
 */
public final class JdbcMigrationTasks {

    private JdbcMigrationTasks() {
    }

    /**
     * Create the default standalone JDBC migration task with permissive risk confirmation.
     *
     * @param dataSource JDBC data source
     * @param definition user-facing task definition
     * @param metadataRegistry encryption metadata registry
     * @param algorithmRegistry algorithm registry
     * @param properties SQL dialect and encryption properties
     * @param stateStore checkpoint state store
     * @return executable migration task
     */
    public static MigrationTask create(DataSource dataSource,
                                       EntityMigrationDefinition definition,
                                       EncryptMetadataRegistry metadataRegistry,
                                       AlgorithmRegistry algorithmRegistry,
                                       DatabaseEncryptionProperties properties,
                                       MigrationStateStore stateStore) {
        return create(dataSource, definition, metadataRegistry, algorithmRegistry, properties, stateStore,
                AllowAllMigrationConfirmationPolicy.INSTANCE);
    }

    /**
     * Create the default standalone JDBC migration task with a custom confirmation policy.
     *
     * @param dataSource JDBC data source
     * @param definition user-facing task definition
     * @param metadataRegistry encryption metadata registry
     * @param algorithmRegistry algorithm registry
     * @param properties SQL dialect and encryption properties
     * @param stateStore checkpoint state store
     * @param confirmationPolicy risk confirmation policy
     * @return executable migration task
     */
    public static MigrationTask create(DataSource dataSource,
                                       EntityMigrationDefinition definition,
                                       EncryptMetadataRegistry metadataRegistry,
                                       AlgorithmRegistry algorithmRegistry,
                                       DatabaseEncryptionProperties properties,
                                       MigrationStateStore stateStore,
                                       MigrationConfirmationPolicy confirmationPolicy) {
        EntityMigrationPlan plan = new EntityMigrationPlanFactory(metadataRegistry).create(definition);
        JdbcMigrationRecordReader recordReader = new JdbcMigrationRecordReader(properties);
        return new JdbcEntityMigrationTask(
                dataSource,
                plan,
                recordReader,
                recordReader,
                new JdbcMigrationRecordWriter(properties, algorithmRegistry, new SnowflakeReferenceIdGenerator()),
                new JdbcMigrationRecordVerifier(properties, algorithmRegistry),
                stateStore,
                confirmationPolicy
        );
    }
}
