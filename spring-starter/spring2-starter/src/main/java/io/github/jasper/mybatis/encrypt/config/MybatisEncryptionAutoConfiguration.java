package io.github.jasper.mybatis.encrypt.config;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.algorithm.AssistedQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.CipherAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.LikeQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.support.*;
import io.github.jasper.mybatis.encrypt.core.decrypt.ResultDecryptor;
import io.github.jasper.mybatis.encrypt.core.metadata.AnnotationEncryptMetadataLoader;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import io.github.jasper.mybatis.encrypt.core.rewrite.SqlRewriteEngine;
import io.github.jasper.mybatis.encrypt.core.support.SeparateTableEncryptionManager;
import io.github.jasper.mybatis.encrypt.plugin.DatabaseEncryptionInterceptor;
import org.apache.ibatis.plugin.Interceptor;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.Map;

/**
 * 字段加密插件自动配置入口。
 *
 * <p>负责注册默认算法、规则中心、SQL 改写器、结果解密器和 MyBatis 拦截器。
 * 业务方可以通过声明同名 Bean 覆盖默认算法实现。</p>
 */
@Configuration(proxyBeanMethods = false)
@AutoConfiguration(afterName = {
        "org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration",
        "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration"
})
@ConditionalOnClass(name = "org.mybatis.spring.SqlSessionFactoryBean", value = Interceptor.class)
@ConditionalOnProperty(prefix = "mybatis.encrypt", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(UserDatabaseEncryptionProperties.class)
public class MybatisEncryptionAutoConfiguration {


    /**
     * 将其 spring properties 转换转换未内部配置项
     * @param userProperties 用户配置
     * @return 内部配置内容
     */
    @Bean
    public DatabaseEncryptionProperties convertProperties(UserDatabaseEncryptionProperties userProperties) {
        DatabaseEncryptionProperties properties = new DatabaseEncryptionProperties();
        BeanUtils.copyProperties(userProperties, properties);
        return properties;
    }

    /**
     * 注册默认国密 SM4 对称加密算法实现。
     *
     * @param properties 插件配置属性
     * @return SM4 密文算法 Bean
     */
    @Bean(name = "sm4")
    @ConditionalOnMissingBean(name = "sm4")
    public CipherAlgorithm sm4CipherAlgorithm(DatabaseEncryptionProperties properties) {
        return new Sm4CipherAlgorithm(properties.getDefaultCipherKey());
    }

    /**
     * 注册默认 AES 对称加密算法实现。
     *
     * @param properties 插件配置属性
     * @return AES 密文算法 Bean
     */
    @Bean(name = "aes")
    @ConditionalOnMissingBean(name = "aes")
    public CipherAlgorithm aesCipherAlgorithm(DatabaseEncryptionProperties properties) {
        return new AesCipherAlgorithm(properties.getDefaultCipherKey());
    }

    /**
     * 注册默认 SM3 辅助查询算法实现。
     *
     * @return SM3 辅助查询算法 Bean
     */
    @Bean(name = "sm3")
    @ConditionalOnMissingBean(name = "sm3")
    public AssistedQueryAlgorithm sm3AssistedQueryAlgorithm(DatabaseEncryptionProperties properties) {
        return new Sm3AssistedQueryAlgorithm(properties.getDefaultHexSlat());
    }

    /**
     * 注册默认 SHA-256 辅助查询算法实现。
     *
     * @return SHA-256 辅助查询算法 Bean
     */
    @Bean(name = "sha256")
    @ConditionalOnMissingBean(name = "sha256")
    public AssistedQueryAlgorithm sha256AssistedQueryAlgorithm() {
        return new Sha256AssistedQueryAlgorithm();
    }

    /**
     * 注册默认 LIKE 预处理算法实现。
     *
     * @return 标准化 LIKE 查询算法 Bean
     */
    @Bean(name = "normalizedLike")
    @ConditionalOnMissingBean(name = "normalizedLike")
    public LikeQueryAlgorithm normalizedLikeQueryAlgorithm() {
        return new NormalizedLikeQueryAlgorithm();
    }

    /**
     * 汇总所有已注册算法为统一算法注册中心。
     *
     * @param cipherAlgorithms 密文算法集合
     * @param assistedAlgorithms 辅助查询算法集合
     * @param likeAlgorithms LIKE 查询算法集合
     * @return 算法注册中心
     */
    @Bean
    public AlgorithmRegistry algorithmRegistry(Map<String, CipherAlgorithm> cipherAlgorithms,
                                               Map<String, AssistedQueryAlgorithm> assistedAlgorithms,
                                               Map<String, LikeQueryAlgorithm> likeAlgorithms) {
        return new AlgorithmRegistry(cipherAlgorithms, assistedAlgorithms, likeAlgorithms);
    }

    /**
     * 创建基于注解的加密元数据加载器。
     *
     * @return 注解元数据加载器
     */
    @Bean
    public AnnotationEncryptMetadataLoader annotationEncryptMetadataLoader() {
        return new AnnotationEncryptMetadataLoader();
    }

    /**
     * 创建运行期加密元数据注册中心。
     *
     * @param properties 插件配置属性
     * @param loader 注解元数据加载器
     * @return 加密元数据注册中心
     */
    @Bean
    public EncryptMetadataRegistry encryptMetadataRegistry(DatabaseEncryptionProperties properties,
                                                           AnnotationEncryptMetadataLoader loader) {
        return new EncryptMetadataRegistry(properties, loader);
    }

    /**
     * 创建实体扫描器，用于启动阶段预热注解元数据。
     *
     * @param beanFactory Spring BeanFactory
     * @param properties 插件配置属性
     * @param metadataRegistry 加密元数据注册中心
     * @return 加密实体扫描器
     */
    @Bean
    public EncryptEntityScanner encryptEntityScanner(BeanFactory beanFactory,
                                                     DatabaseEncryptionProperties properties,
                                                     EncryptMetadataRegistry metadataRegistry) {
        return new EncryptEntityScanner(beanFactory, properties, metadataRegistry);
    }

    /**
     * 创建查询结果解密器。
     *
     * @param metadataRegistry 加密元数据注册中心
     * @param algorithmRegistry 算法注册中心
     * @param managers 独立表加密管理器集合
     * @return 结果解密器
     */
    @Bean
    public ResultDecryptor resultDecryptor(EncryptMetadataRegistry metadataRegistry,
                                           AlgorithmRegistry algorithmRegistry,
                                           Map<String, SeparateTableEncryptionManager> managers) {
        return new ResultDecryptor(metadataRegistry, algorithmRegistry,
                managers.values().stream().findFirst().orElse(null));
    }

    /**
     * 创建 SQL 改写引擎。
     *
     * @param metadataRegistry 加密元数据注册中心
     * @param algorithmRegistry 算法注册中心
     * @param properties 插件配置属性
     * @return SQL 改写引擎
     */
    @Bean
    public SqlRewriteEngine sqlRewriteEngine(EncryptMetadataRegistry metadataRegistry,
                                             AlgorithmRegistry algorithmRegistry,
                                             DatabaseEncryptionProperties properties) {
        return new SqlRewriteEngine(metadataRegistry, algorithmRegistry, properties);
    }

    /**
     * 创建 MyBatis 加密拦截器。
     *
     * @param sqlRewriteEngine SQL 改写引擎
     * @param resultDecryptor 查询结果解密器
     * @param properties 插件配置属性
     * @param metadataRegistry 加密元数据注册中心
     * @param managers 独立表加密管理器集合
     * @return MyBatis 加密拦截器
     */
    @Bean
    public DatabaseEncryptionInterceptor databaseEncryptionInterceptor(SqlRewriteEngine sqlRewriteEngine,
                                                                       ResultDecryptor resultDecryptor,
                                                                       DatabaseEncryptionProperties properties,
                                                                       EncryptMetadataRegistry metadataRegistry,
                                                                       Map<String, SeparateTableEncryptionManager> managers) {
        return new DatabaseEncryptionInterceptor(sqlRewriteEngine, resultDecryptor, properties,
                managers.values().stream().findFirst().orElse(null), metadataRegistry);
    }

    /**
     * 在存在数据源时创建独立表加密管理器。
     *
     * @param dataSource 数据源
     * @param metadataRegistry 加密元数据注册中心
     * @param algorithmRegistry 算法注册中心
     * @param properties 插件配置属性
     * @return 独立表加密管理器
     */
    @Bean
    @ConditionalOnBean(DataSource.class)
    public SeparateTableEncryptionManager separateTableEncryptionManager(DataSource dataSource,
                                                                         EncryptMetadataRegistry metadataRegistry,
                                                                         AlgorithmRegistry algorithmRegistry,
                                                                         DatabaseEncryptionProperties properties) {
        return new SeparateTableEncryptionManager(dataSource, metadataRegistry, algorithmRegistry, properties);
    }

}
