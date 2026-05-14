package io.github.jasper.mybatis.encrypt.config;

import io.github.jasper.mybatis.encrypt.logsafe.context.DefaultMdcContributor;
import io.github.jasper.mybatis.encrypt.logsafe.context.MdcContributor;
import io.github.jasper.mybatis.encrypt.logsafe.context.web.DefaultTraceIdResolver;
import io.github.jasper.mybatis.encrypt.logsafe.context.web.TraceContextInterceptor;
import io.github.jasper.mybatis.encrypt.logsafe.context.web.TraceContextWebMvcConfigurer;
import io.github.jasper.mybatis.encrypt.logsafe.context.web.TraceIdResolver;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC auto-configuration for logsafe MDC request context.
 */
@Configuration(proxyBeanMethods = false)
@AutoConfigureAfter(LogsafeAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({HandlerInterceptor.class, WebMvcConfigurer.class})
@ConditionalOnProperty(prefix = "mybatis.encrypt.logsafe.context", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(LogsafeProperties.class)
public class LogsafeContextAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TraceIdResolver traceIdResolver(LogsafeProperties properties) {
        return new DefaultTraceIdResolver(
                properties.getContext().getHeader().getTraceId(),
                properties.getContext().getHeader().getRequestId());
    }

    @Bean
    @ConditionalOnMissingBean
    public MdcContributor mdcContributor(LogsafeProperties properties) {
        return new DefaultMdcContributor(
                properties.getContext().getKeys().isTenantIdEnabled(),
                properties.getContext().getKeys().isUserIdEnabled(),
                properties.getContext().getKeys().isClientIpEnabled());
    }

    @Bean
    @ConditionalOnMissingBean
    public TraceContextInterceptor traceContextInterceptor(TraceIdResolver traceIdResolver,
                                                           MdcContributor mdcContributor) {
        return new TraceContextInterceptor(traceIdResolver, mdcContributor);
    }

    @Bean
    @ConditionalOnMissingBean
    public TraceContextWebMvcConfigurer traceContextWebMvcConfigurer(TraceContextInterceptor interceptor) {
        return new TraceContextWebMvcConfigurer(interceptor);
    }
}
