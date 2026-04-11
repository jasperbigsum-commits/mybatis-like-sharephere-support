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

    public static MigrationTask create(DataSource dataSource,
                                       EntityMigrationDefinition definition,
                                       EncryptMetadataRegistry metadataRegistry,
                                       AlgorithmRegistry algorithmRegistry,
                                       DatabaseEncryptionProperties properties,
                                       MigrationStateStore stateStore) {
        return create(dataSource, definition, metadataRegistry, algorithmRegistry, properties, stateStore,
                AllowAllMigrationConfirmationPolicy.INSTANCE);
    }

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
