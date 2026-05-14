package io.github.jasper.mybatis.encrypt.config;

import io.github.jasper.mybatis.encrypt.logsafe.context.async.MdcTaskDecorator;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;

/**
 * Auto-configuration for logsafe MDC propagation across asynchronous Spring tasks.
 */
@Configuration(proxyBeanMethods = false)
@AutoConfigureAfter(name = "io.github.jasper.mybatis.encrypt.config.LogsafeContextAutoConfiguration")
@ConditionalOnClass(TaskDecorator.class)
@ConditionalOnProperty(prefix = "mybatis.encrypt.logsafe.context.propagation", name = "async-enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(LogsafeProperties.class)
public class LogsafeAsyncContextAutoConfiguration {

    /**
     * Creates the Spring task decorator that captures and restores MDC around async tasks.
     *
     * @return MDC task decorator bean
     */
    @Bean
    @ConditionalOnMissingBean
    public MdcTaskDecorator mdcTaskDecorator() {
        return new MdcTaskDecorator();
    }

    /**
     * Exposes the logsafe MDC decorator as Spring's general {@link TaskDecorator} when none exists.
     *
     * @param mdcTaskDecorator logsafe MDC task decorator
     * @return task decorator bean
     */
    @Bean
    @ConditionalOnMissingBean(TaskDecorator.class)
    public TaskDecorator logsafeTaskDecorator(MdcTaskDecorator mdcTaskDecorator) {
        return mdcTaskDecorator;
    }
}
