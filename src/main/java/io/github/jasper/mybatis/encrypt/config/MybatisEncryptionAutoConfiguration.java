package io.github.jasper.mybatis.encrypt.config;

import java.util.Map;
import javax.sql.DataSource;
import org.apache.ibatis.plugin.Interceptor;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.algorithm.AssistedQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.CipherAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.LikeQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.support.AesCipherAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.support.NormalizedLikeQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.support.Sha256AssistedQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.support.Sm3AssistedQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.support.Sm4CipherAlgorithm;
import io.github.jasper.mybatis.encrypt.core.decrypt.ResultDecryptor;
import io.github.jasper.mybatis.encrypt.core.metadata.AnnotationEncryptMetadataLoader;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import io.github.jasper.mybatis.encrypt.core.rewrite.SqlRewriteEngine;
import io.github.jasper.mybatis.encrypt.core.support.SeparateTableEncryptionManager;
import io.github.jasper.mybatis.encrypt.plugin.DatabaseEncryptionInterceptor;
import org.springframework.context.annotation.Configuration;

/**
 * 字段加密插件自动配置入口。
 *
 * <p>负责注册默认算法、规则中心、SQL 改写器、结果解密器和 MyBatis 拦截器。
 * 业务方可以通过声明同名 Bean 覆盖默认算法实现。</p>
 */
@Configuration(proxyBeanMethods = false)
@AutoConfiguration(afterName = {
        "org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration",
        ""
})
@ConditionalOnClass(name = "org.mybatis.spring.SqlSessionFactoryBean", value = Interceptor.class)
@ConditionalOnProperty(prefix = "mybatis.encrypt", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(DatabaseEncryptionProperties.class)
public class MybatisEncryptionAutoConfiguration {

    @Bean(name = "sm4")
    @ConditionalOnMissingBean(name = "sm4")
    public CipherAlgorithm sm4CipherAlgorithm(DatabaseEncryptionProperties properties) {
        return new Sm4CipherAlgorithm(properties.getDefaultCipherKey());
    }

    @Bean(name = "aes")
    @ConditionalOnMissingBean(name = "aes")
    public CipherAlgorithm aesCipherAlgorithm(DatabaseEncryptionProperties properties) {
        return new AesCipherAlgorithm(properties.getDefaultCipherKey());
    }

    @Bean(name = "sm3")
    @ConditionalOnMissingBean(name = "sm3")
    public AssistedQueryAlgorithm sm3AssistedQueryAlgorithm() {
        return new Sm3AssistedQueryAlgorithm();
    }

    @Bean(name = "sha256")
    @ConditionalOnMissingBean(name = "sha256")
    public AssistedQueryAlgorithm sha256AssistedQueryAlgorithm() {
        return new Sha256AssistedQueryAlgorithm();
    }

    @Bean(name = "normalizedLike")
    @ConditionalOnMissingBean(name = "normalizedLike")
    public LikeQueryAlgorithm normalizedLikeQueryAlgorithm() {
        return new NormalizedLikeQueryAlgorithm();
    }

    @Bean
    public AlgorithmRegistry algorithmRegistry(Map<String, CipherAlgorithm> cipherAlgorithms,
                                               Map<String, AssistedQueryAlgorithm> assistedAlgorithms,
                                               Map<String, LikeQueryAlgorithm> likeAlgorithms) {
        return new AlgorithmRegistry(cipherAlgorithms, assistedAlgorithms, likeAlgorithms);
    }

    @Bean
    public AnnotationEncryptMetadataLoader annotationEncryptMetadataLoader() {
        return new AnnotationEncryptMetadataLoader();
    }

    @Bean
    public EncryptMetadataRegistry encryptMetadataRegistry(DatabaseEncryptionProperties properties,
                                                           AnnotationEncryptMetadataLoader loader) {
        return new EncryptMetadataRegistry(properties, loader);
    }

    @Bean
    public EncryptEntityScanner encryptEntityScanner(BeanFactory beanFactory,
                                                     DatabaseEncryptionProperties properties,
                                                     EncryptMetadataRegistry metadataRegistry) {
        return new EncryptEntityScanner(beanFactory, properties, metadataRegistry);
    }

    @Bean
    public ResultDecryptor resultDecryptor(EncryptMetadataRegistry metadataRegistry,
                                           AlgorithmRegistry algorithmRegistry,
                                           Map<String, SeparateTableEncryptionManager> managers) {
        return new ResultDecryptor(metadataRegistry, algorithmRegistry,
                managers.values().stream().findFirst().orElse(null));
    }

    @Bean
    public SqlRewriteEngine sqlRewriteEngine(EncryptMetadataRegistry metadataRegistry,
                                             AlgorithmRegistry algorithmRegistry,
                                             DatabaseEncryptionProperties properties) {
        return new SqlRewriteEngine(metadataRegistry, algorithmRegistry, properties);
    }

    @Bean
    public DatabaseEncryptionInterceptor databaseEncryptionInterceptor(SqlRewriteEngine sqlRewriteEngine,
                                                                       ResultDecryptor resultDecryptor,
                                                                       DatabaseEncryptionProperties properties,
                                                                       Map<String, SeparateTableEncryptionManager> managers) {
        return new DatabaseEncryptionInterceptor(sqlRewriteEngine, resultDecryptor, properties,
                managers.values().stream().findFirst().orElse(null));
    }

    @Bean
    @ConditionalOnBean(DataSource.class)
    public SeparateTableEncryptionManager separateTableEncryptionManager(DataSource dataSource,
                                                                         EncryptMetadataRegistry metadataRegistry,
                                                                         AlgorithmRegistry algorithmRegistry,
                                                                         DatabaseEncryptionProperties properties) {
        return new SeparateTableEncryptionManager(dataSource, metadataRegistry, algorithmRegistry, properties);
    }

}
