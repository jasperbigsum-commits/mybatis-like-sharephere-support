package io.github.jasper.mybatis.encrypt.config;

import io.github.jasper.mybatis.encrypt.logsafe.LogsafeTextMasker;
import io.github.jasper.mybatis.encrypt.logsafe.logback.LogsafeLogbackAppenderInstaller;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration that attaches logsafe terminal masking to Logback when Logback is present.
 */
@Configuration(proxyBeanMethods = false)
@AutoConfigureAfter(LogsafeAutoConfiguration.class)
@ConditionalOnClass(name = {
        "ch.qos.logback.classic.LoggerContext",
        "ch.qos.logback.classic.spi.ILoggingEvent",
        "ch.qos.logback.core.Appender"
})
@ConditionalOnBean(LogsafeTextMasker.class)
@ConditionalOnProperty(prefix = "mybatis.encrypt.logsafe.terminal", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(LogsafeProperties.class)
public class LogsafeLogbackAutoConfiguration {

    /**
     * Creates the Logback appender installer for terminal log masking.
     *
     * @param textMasker text masker used by the Logback filter
     * @return Logback installer bean
     */
    @Bean
    @ConditionalOnMissingBean
    public LogsafeLogbackAppenderInstaller logsafeLogbackAppenderInstaller(LogsafeTextMasker textMasker) {
        return new LogsafeLogbackAppenderInstaller(textMasker);
    }

    /**
     * Installs the Logback masking filter after all singleton beans are available.
     *
     * @param installer Logback installer bean
     * @return lifecycle callback that performs installation
     */
    @Bean
    @ConditionalOnMissingBean(name = "logsafeLogbackInstallerLifecycle")
    public SmartInitializingSingleton logsafeLogbackInstallerLifecycle(
            LogsafeLogbackAppenderInstaller installer) {
        return new SmartInitializingSingleton() {
            @Override
            public void afterSingletonsInstantiated() {
                installer.install();
            }
        };
    }
}
