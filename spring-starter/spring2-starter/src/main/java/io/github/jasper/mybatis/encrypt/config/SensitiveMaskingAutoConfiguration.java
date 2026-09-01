package io.github.jasper.mybatis.encrypt.config;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.core.mask.JdbcStoredSensitiveValueResolver;
import io.github.jasper.mybatis.encrypt.core.mask.SensitiveDataMasker;
import io.github.jasper.mybatis.encrypt.core.mask.SensitiveFieldMasker;
import io.github.jasper.mybatis.encrypt.core.mask.StoredSensitiveValueResolver;
import io.github.jasper.mybatis.encrypt.support.SensitiveResponseTriggerAspect;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.util.Map;

/**
 * Core auto-configuration for response masking components that are not tied to MVC.
 */
@AutoConfiguration(after = MybatisEncryptionAutoConfiguration.class)
@ConditionalOnProperty(prefix = "mybatis.encrypt.sensitive-response", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class SensitiveMaskingAutoConfiguration {

    /**
     * 注入敏感信息脱敏器
     * @param storedSensitiveValueResolver 存储敏感信息解决器
     * @param algorithmRegistry 算法注册表
     * @param sensitiveFieldMaskers 敏感字段脱敏器
     * @return 敏感信息脱敏器
     */
    @Bean
    @ConditionalOnMissingBean
    public SensitiveDataMasker sensitiveDataMasker(
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            StoredSensitiveValueResolver storedSensitiveValueResolver,
            AlgorithmRegistry algorithmRegistry,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            Map<String, SensitiveFieldMasker> sensitiveFieldMaskers) {
        return new SensitiveDataMasker(storedSensitiveValueResolver, algorithmRegistry, sensitiveFieldMaskers);
    }

    /**
     * 注入存储敏感信息解决器
     * @param dataSources 数据源
     * @param algorithmRegistry 注册表
     * @param properties 配置参数
     * @return 存储敏感信息解决器
     */
    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(StoredSensitiveValueResolver.class)
    public StoredSensitiveValueResolver storedSensitiveValueResolver(Map<String, DataSource> dataSources,
                                                                    AlgorithmRegistry algorithmRegistry,
                                                                    DatabaseEncryptionProperties properties) {
        return new JdbcStoredSensitiveValueResolver(dataSources, algorithmRegistry, properties);
    }

    /**
     * 注入敏感信息响应触发器器切面
     * @param sensitiveDataMasker 敏感信息脱敏器
     * @return 敏感信息响应触发器器切面
     */
    @Bean
    @ConditionalOnClass(Aspect.class)
    @ConditionalOnMissingBean
    public SensitiveResponseTriggerAspect sensitiveResponseTriggerAspect(SensitiveDataMasker sensitiveDataMasker) {
        return new SensitiveResponseTriggerAspect(sensitiveDataMasker);
    }
}
