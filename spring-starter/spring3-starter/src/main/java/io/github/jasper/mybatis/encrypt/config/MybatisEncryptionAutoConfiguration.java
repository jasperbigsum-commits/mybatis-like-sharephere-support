package io.github.jasper.mybatis.encrypt.config;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.algorithm.AssistedQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.CipherAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.LikeQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.support.*;
import io.github.jasper.mybatis.encrypt.core.decrypt.ResultDecryptor;
import io.github.jasper.mybatis.encrypt.core.support.DataSourceNameResolver;
import io.github.jasper.mybatis.encrypt.core.metadata.AnnotationEncryptMetadataLoader;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import io.github.jasper.mybatis.encrypt.core.rewrite.SqlRewriteEngine;
import io.github.jasper.mybatis.encrypt.core.support.DefaultSeparateTableRowPersister;
import io.github.jasper.mybatis.encrypt.core.support.RoutingSeparateTableEncryptionManager;
import io.github.jasper.mybatis.encrypt.core.support.SeparateTableEncryptionManager;
import io.github.jasper.mybatis.encrypt.core.support.SeparateTableRowPersister;
import io.github.jasper.mybatis.encrypt.migration.AllowAllMigrationConfirmationPolicy;
import io.github.jasper.mybatis.encrypt.migration.DefaultGlobalMigrationTaskFactory;
import io.github.jasper.mybatis.encrypt.migration.DefaultGlobalMigrationSchemaSqlGeneratorFactory;
import io.github.jasper.mybatis.encrypt.migration.DefaultMigrationTaskFactory;
import io.github.jasper.mybatis.encrypt.migration.FileMigrationStateStore;
import io.github.jasper.mybatis.encrypt.migration.GlobalMigrationTaskFactory;
import io.github.jasper.mybatis.encrypt.migration.GlobalMigrationSchemaSqlGeneratorFactory;
import io.github.jasper.mybatis.encrypt.migration.MigrationConfirmationPolicy;
import io.github.jasper.mybatis.encrypt.migration.MigrationSchemaSqlGenerator;
import io.github.jasper.mybatis.encrypt.migration.MigrationStateStore;
import io.github.jasper.mybatis.encrypt.migration.MigrationTaskFactory;
import io.github.jasper.mybatis.encrypt.plugin.DatabaseEncryptionInterceptor;
import io.github.jasper.mybatis.encrypt.plugin.CompositeWriteParameterPreprocessor;
import io.github.jasper.mybatis.encrypt.plugin.WriteParameterPreprocessor;
import org.apache.ibatis.plugin.Interceptor;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
     * @param properties 插件配置属性
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
     * 注册默认身份证号脱敏 LIKE 算法实现。
     *
     * @return 身份证号脱敏 LIKE 算法 Bean
     */
    @Bean(name = "idCardMaskLike")
    @ConditionalOnMissingBean(name = "idCardMaskLike")
    public LikeQueryAlgorithm idCardMaskLikeQueryAlgorithm() {
        return new IdCardMaskLikeQueryAlgorithm();
    }

    /**
     * 注册默认手机号/座机号脱敏 LIKE 算法实现。
     *
     * @return 手机号/座机号脱敏 LIKE 算法 Bean
     */
    @Bean(name = "phoneMaskLike")
    @ConditionalOnMissingBean(name = "phoneMaskLike")
    public LikeQueryAlgorithm phoneMaskLikeQueryAlgorithm() {
        return new PhoneNumberMaskLikeQueryAlgorithm();
    }

    /**
     * 注册默认银行卡号脱敏 LIKE 算法实现。
     *
     * @return 银行卡号脱敏 LIKE 算法 Bean
     */
    @Bean(name = "bankCardMaskLike")
    @ConditionalOnMissingBean(name = "bankCardMaskLike")
    public LikeQueryAlgorithm bankCardMaskLikeQueryAlgorithm() {
        return new BankCardMaskLikeQueryAlgorithm();
    }

    /**
     * 注册默认中文名称脱敏 LIKE 算法实现。
     *
     * @return 中文名称脱敏 LIKE 算法 Bean
     */
    @Bean(name = "nameMaskLike")
    @ConditionalOnMissingBean(name = "nameMaskLike")
    public LikeQueryAlgorithm nameMaskLikeQueryAlgorithm() {
        return new NameMaskLikeQueryAlgorithm();
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
     * @param separateTableEncryptionManager 独立表加密管理器集合
     * @return 结果解密器
     */
    @Bean
    public ResultDecryptor resultDecryptor(EncryptMetadataRegistry metadataRegistry,
                                           AlgorithmRegistry algorithmRegistry,
                                           @org.springframework.beans.factory.annotation.Autowired(required = false)
                                           SeparateTableEncryptionManager separateTableEncryptionManager) {
        return new ResultDecryptor(metadataRegistry, algorithmRegistry, separateTableEncryptionManager);
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
     * Combines all discovered write-parameter preprocessors into one delegate.
     *
     * @param preprocessors optional preprocessor beans
     * @return composite write-parameter preprocessor
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean(name = "compositeWriteParameterPreprocessor")
    public WriteParameterPreprocessor compositeWriteParameterPreprocessor(
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            List<WriteParameterPreprocessor> preprocessors) {
        List<WriteParameterPreprocessor> delegates = new ArrayList<WriteParameterPreprocessor>();
        if (preprocessors != null) {
            for (WriteParameterPreprocessor preprocessor : preprocessors) {
                if (!(preprocessor instanceof CompositeWriteParameterPreprocessor)) {
                    delegates.add(preprocessor);
                }
            }
        }
        return new CompositeWriteParameterPreprocessor(delegates);
    }

    /**
     * Registers the built-in JEECG-compatible write preprocessor when JEECG interceptor classes are
     * present on the classpath.
     *
     * @param beanFactory Spring bean factory
     * @return JEECG-compatible write preprocessor
     */
    @Bean
    @ConditionalOnClass(name = "org.jeecg.config.mybatis.MybatisInterceptor")
    @ConditionalOnMissingBean(name = "jeecgWriteParameterPreprocessor")
    public WriteParameterPreprocessor jeecgWriteParameterPreprocessor(BeanFactory beanFactory) {
        return new JeecgWriteParameterPreprocessor(beanFactory);
    }

    /**
     * 创建 MyBatis 加密拦截器。
     *
     * @param sqlRewriteEngine SQL 改写引擎
     * @param resultDecryptor 查询结果解密器
     * @param properties 插件配置属性
     * @param metadataRegistry 加密元数据注册中心
     * @param separateTableEncryptionManager 独立表加密管理器集合
     * @param dataSourceNameResolver 数据名称处理器
     * @return MyBatis 加密拦截器
     */
    @Bean
    public DatabaseEncryptionInterceptor databaseEncryptionInterceptor(SqlRewriteEngine sqlRewriteEngine,
                                                                       ResultDecryptor resultDecryptor,
                                                                       DatabaseEncryptionProperties properties,
                                                                       EncryptMetadataRegistry metadataRegistry,
                                                                       @org.springframework.beans.factory.annotation.Autowired(required = false)
                                                                       SeparateTableEncryptionManager separateTableEncryptionManager,
                                                                       @org.springframework.beans.factory.annotation.Autowired(required = false)
                                                                       DataSourceNameResolver dataSourceNameResolver,
                                                                       @org.springframework.beans.factory.annotation.Autowired(required = false)
                                                                       WriteParameterPreprocessor writeParameterPreprocessor) {
        return new DatabaseEncryptionInterceptor(sqlRewriteEngine, resultDecryptor, properties,
                separateTableEncryptionManager, metadataRegistry, dataSourceNameResolver,
                writeParameterPreprocessor);
    }

    /**
     * 在存在数据源且未自定义写入器时创建默认独立表写入执行器。
     *
     * @param dataSource 数据源
     * @param properties 插件配置属性
     * @return 独立表写入执行器
     */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    @ConditionalOnMissingBean(SeparateTableRowPersister.class)
    public SeparateTableRowPersister separateTableRowPersister(DataSource dataSource,
                                                               DatabaseEncryptionProperties properties) {
        return new DefaultSeparateTableRowPersister(dataSource, properties);
    }

    /**
     * 创建当前上下文使用的数据源名称解析器。
     *
     * @param dataSources 数据源集合
     * @return 数据源名称解析器
     */
    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(DataSourceNameResolver.class)
    public DataSourceNameResolver dataSourceNameResolver(Map<String, DataSource> dataSources) {
        return new DataSourceNameResolver(dataSources);
    }

    /**
     * 在存在数据源时创建独立表加密管理器。
     *
     * @param dataSources 数据源
     * @param metadataRegistry 加密元数据注册中心
     * @param algorithmRegistry 算法注册中心
     * @param properties 插件配置属性
     * @param rowPersister 独立表写入执行器
     * @return 独立表加密管理器
     */
    @Bean
    @ConditionalOnBean(DataSource.class)
    public SeparateTableEncryptionManager separateTableEncryptionManager(
            Map<String, DataSource> dataSources,
            EncryptMetadataRegistry metadataRegistry,
            AlgorithmRegistry algorithmRegistry,
            DatabaseEncryptionProperties properties,
            @org.springframework.beans.factory.annotation.Autowired(required = false) SeparateTableRowPersister rowPersister) {
        if (dataSources.size() <= 1) {
            DataSource dataSource = dataSources.values().stream().findFirst().orElse(null);
            if (dataSource == null) {
                return null;
            }
            SeparateTableRowPersister effectiveRowPersister = rowPersister != null
                    ? rowPersister : new DefaultSeparateTableRowPersister(dataSource, properties);
            return new SeparateTableEncryptionManager(
                    dataSource, metadataRegistry, algorithmRegistry, properties, effectiveRowPersister);
        }
        Map<String, SeparateTableEncryptionManager> managers = new LinkedHashMap<String, SeparateTableEncryptionManager>();
        for (Map.Entry<String, DataSource> entry : dataSources.entrySet()) {
            managers.put(entry.getKey(), new SeparateTableEncryptionManager(
                    entry.getValue(),
                    metadataRegistry,
                    algorithmRegistry,
                    properties,
                    new DefaultSeparateTableRowPersister(entry.getValue(), properties)));
        }
        return new RoutingSeparateTableEncryptionManager(managers);
    }

    /**
     * 创建默认迁移状态存储器。
     *
     * @param properties 插件配置属性
     * @return 默认文件状态存储器
     */
    @Bean
    @ConditionalOnMissingBean(MigrationStateStore.class)
    public MigrationStateStore migrationStateStore(DatabaseEncryptionProperties properties) {
        return new FileMigrationStateStore(Paths.get(properties.getMigration().getCheckpointDirectory()));
    }

    /**
     * 创建默认迁移确认策略。
     *
     * @return 默认放行确认策略
     */
    @Bean
    @ConditionalOnMissingBean(MigrationConfirmationPolicy.class)
    public MigrationConfirmationPolicy migrationConfirmationPolicy() {
        return AllowAllMigrationConfirmationPolicy.INSTANCE;
    }

    /**
     * 创建迁移任务工厂，简化 Spring 场景下的任务构建。
     *
     * @param dataSources 数据源
     * @param metadataRegistry 加密元数据注册中心
     * @param algorithmRegistry 算法注册中心
     * @param properties 插件配置属性
     * @param stateStore 状态存储器
     * @param confirmationPolicy 确认策略
     * @return 迁移任务工厂
     */
    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(GlobalMigrationTaskFactory.class)
    public GlobalMigrationTaskFactory globalMigrationTaskFactory(Map<String, DataSource> dataSources,
                                                                 EncryptMetadataRegistry metadataRegistry,
                                                                 AlgorithmRegistry algorithmRegistry,
                                                                 DatabaseEncryptionProperties properties,
                                                                 MigrationStateStore stateStore,
                                                                 MigrationConfirmationPolicy confirmationPolicy) {
        return new DefaultGlobalMigrationTaskFactory(dataSources, metadataRegistry, algorithmRegistry, properties,
                stateStore, confirmationPolicy);
    }

    /**
     * 创建全局 DDL 生成工厂，便于多数据源场景按路由批量输出 schema 变更 SQL。
     *
     * @param dataSources 数据源集合
     * @param metadataRegistry 加密元数据注册中心
     * @param properties 插件配置属性
     * @return 全局 DDL 生成工厂
     */
    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(GlobalMigrationSchemaSqlGeneratorFactory.class)
    public GlobalMigrationSchemaSqlGeneratorFactory globalMigrationSchemaSqlGeneratorFactory(
            Map<String, DataSource> dataSources,
            EncryptMetadataRegistry metadataRegistry,
            DatabaseEncryptionProperties properties) {
        return new DefaultGlobalMigrationSchemaSqlGeneratorFactory(dataSources, metadataRegistry, properties);
    }

    /**
     * 创建迁移任务工厂，简化 Spring 场景下的任务构建。
     *
     * @param dataSource 数据源
     * @param dataSourceNameResolver 数据源名称解析器
     * @param metadataRegistry 加密元数据注册中心
     * @param algorithmRegistry 算法注册中心
     * @param properties 插件配置属性
     * @param stateStore 状态存储器
     * @param confirmationPolicy 确认策略
     * @return 迁移任务工厂
     */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    @ConditionalOnMissingBean(MigrationTaskFactory.class)
    public MigrationTaskFactory migrationTaskFactory(DataSource dataSource,
                                                     @org.springframework.beans.factory.annotation.Autowired(required = false)
                                                     DataSourceNameResolver dataSourceNameResolver,
                                                     EncryptMetadataRegistry metadataRegistry,
                                                     AlgorithmRegistry algorithmRegistry,
                                                     DatabaseEncryptionProperties properties,
                                                     MigrationStateStore stateStore,
                                                     MigrationConfirmationPolicy confirmationPolicy) {
        String dataSourceName = dataSourceNameResolver == null ? null : dataSourceNameResolver.resolve(dataSource);
        return new DefaultMigrationTaskFactory(dataSource, dataSourceName, metadataRegistry, algorithmRegistry, properties,
                stateStore, confirmationPolicy);
    }

    /**
     * 创建单数据源 DDL 生成器，便于直接按当前默认数据源输出 schema 变更 SQL。
     *
     * @param dataSource 数据源
     * @param dataSourceNameResolver 数据源名称解析器
     * @param metadataRegistry 加密元数据注册中心
     * @param properties 插件配置属性
     * @return 单数据源 DDL 生成器
     */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    @ConditionalOnMissingBean(MigrationSchemaSqlGenerator.class)
    public MigrationSchemaSqlGenerator migrationSchemaSqlGenerator(
            DataSource dataSource,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            DataSourceNameResolver dataSourceNameResolver,
            EncryptMetadataRegistry metadataRegistry,
            DatabaseEncryptionProperties properties) {
        String dataSourceName = dataSourceNameResolver == null ? null : dataSourceNameResolver.resolve(dataSource);
        return new MigrationSchemaSqlGenerator(dataSource, dataSourceName, metadataRegistry, properties, null);
    }

}
