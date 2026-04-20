package io.github.jasper.mybatis.encrypt.migration;

import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.config.SqlDialect;
import io.github.jasper.mybatis.encrypt.core.metadata.AnnotationEncryptMetadataLoader;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("全局 DDL 生成工厂")
class GlobalMigrationSchemaSqlGeneratorFactoryTest extends MigrationJdbcTestSupport {

    @Test
    void shouldRouteBatchDdlGenerationByDatasourceNameAndDialect() throws Exception {
        DataSource primary = newDataSource("global_schema_primary");
        DataSource archive = newDataSource("global_schema_archive");
        executeSql(primary,
                "create table user_account (id bigint primary key, phone varchar(64))");
        executeSql(archive,
                "create table user_account (id bigint primary key, phone varchar(64))");

        DatabaseEncryptionProperties properties = configuredProperties();
        DatabaseEncryptionProperties.DataSourceDialectRuleProperties archiveDialect =
                new DatabaseEncryptionProperties.DataSourceDialectRuleProperties();
        archiveDialect.setDatasourceNamePattern("archiveDs");
        archiveDialect.setSqlDialect(SqlDialect.ORACLE12);
        properties.getDatasourceDialects().add(archiveDialect);

        EncryptMetadataRegistry registry = new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader());
        registry.registerEntityType(SameTableUserEntity.class);

        Map<String, DataSource> dataSources = new LinkedHashMap<String, DataSource>();
        dataSources.put("primaryDs", primary);
        dataSources.put("archiveDs", archive);

        GlobalMigrationSchemaSqlGeneratorFactory factory =
                new DefaultGlobalMigrationSchemaSqlGeneratorFactory(dataSources, registry, properties);

        List<String> primaryDdl = factory.generateAllRegisteredTables("primaryDs");
        List<String> archiveDdl = factory.generateAllRegisteredTables("archiveDs");

        assertEquals(Arrays.asList(
                "alter table `user_account` add column `phone_cipher` varchar(110)",
                "alter table `user_account` add column `phone_hash` varchar(64)",
                "alter table `user_account` add column `phone_like` varchar(64)"
        ), primaryDdl);
        assertEquals(Arrays.asList(
                "alter table \"user_account\" add (\"phone_cipher\" varchar2(110))",
                "alter table \"user_account\" add (\"phone_hash\" varchar2(64))",
                "alter table \"user_account\" add (\"phone_like\" varchar2(64))"
        ), archiveDdl);
    }
}
