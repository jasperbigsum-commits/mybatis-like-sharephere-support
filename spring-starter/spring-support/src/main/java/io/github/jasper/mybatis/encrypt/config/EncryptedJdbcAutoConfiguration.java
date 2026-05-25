package io.github.jasper.mybatis.encrypt.config;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.core.decrypt.ResultDecryptor;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import io.github.jasper.mybatis.encrypt.core.rewrite.SqlRewriteEngine;
import io.github.jasper.mybatis.encrypt.core.support.DataSourceNameResolver;
import io.github.jasper.mybatis.encrypt.jdbc.DefaultEncryptedJdbcExecutor;
import io.github.jasper.mybatis.encrypt.jdbc.EncryptedJdbcExecutor;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.Map;

/**
 * Auto-configuration for the encrypted JDBC facade.
 */
@Configuration(proxyBeanMethods = false)
@AutoConfigureAfter(name = "io.github.jasper.mybatis.encrypt.config.MybatisEncryptionAutoConfiguration")
@ConditionalOnClass(EncryptedJdbcExecutor.class)
public class EncryptedJdbcAutoConfiguration {

    /**
     * Create the encrypted JDBC facade bean when a data source is available.
     *
     * @param dataSources available Spring data sources keyed by bean name
     * @param dataSourceNameResolver optional resolver for configured data source names
     * @param sqlRewriteEngine SQL rewrite engine shared with mapper execution
     * @param resultDecryptor result decryptor shared with mapper execution
     * @param metadataRegistry encryption metadata registry
     * @param properties encryption configuration properties
     * @return encrypted JDBC facade
     */
    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(EncryptedJdbcExecutor.class)
    public EncryptedJdbcExecutor encryptedJdbcExecutor(Map<String, DataSource> dataSources,
                                                       @Autowired(required = false) DataSourceNameResolver dataSourceNameResolver,
                                                       SqlRewriteEngine sqlRewriteEngine,
                                                       ResultDecryptor resultDecryptor,
                                                       EncryptMetadataRegistry metadataRegistry,
                                                       DatabaseEncryptionProperties properties) {
        return new DefaultEncryptedJdbcExecutor(dataSources, dataSourceNameResolver, sqlRewriteEngine,
                resultDecryptor, metadataRegistry, properties);
    }
}
