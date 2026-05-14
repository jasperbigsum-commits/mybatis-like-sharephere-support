package io.github.jasper.mybatis.encrypt.config;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.logsafe.LogsafeMasker;
import io.github.jasper.mybatis.encrypt.logsafe.LogsafeTextMasker;
import io.github.jasper.mybatis.encrypt.logsafe.SafeLog;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot auto-configuration for the lightweight logsafe API.
 *
 * <p>This extension intentionally stays independent from controller-boundary response masking.
 * It reuses the already-registered algorithm registry and {@code @SensitiveField} metadata, but it
 * does not alter the response masking pipeline or create request-scoped state.</p>
 */
@Configuration(proxyBeanMethods = false)
@AutoConfigureAfter(name = "io.github.jasper.mybatis.encrypt.config.MybatisEncryptionAutoConfiguration")
@ConditionalOnClass(SafeLog.class)
@ConditionalOnProperty(prefix = "mybatis.encrypt.logsafe", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(LogsafeProperties.class)
public class LogsafeAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public LogsafeMasker logsafeMasker(AlgorithmRegistry algorithmRegistry) {
        return new LogsafeMasker(algorithmRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public SafeLog safeLog(LogsafeMasker logsafeMasker) {
        return new SafeLog(logsafeMasker);
    }

    @Bean
    @ConditionalOnMissingBean
    public LogsafeTextMasker logsafeTextMasker(LogsafeMasker logsafeMasker) {
        return new LogsafeTextMasker(logsafeMasker);
    }
}
