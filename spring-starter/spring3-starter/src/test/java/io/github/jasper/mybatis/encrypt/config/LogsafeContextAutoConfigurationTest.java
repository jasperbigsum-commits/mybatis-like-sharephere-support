package io.github.jasper.mybatis.encrypt.config;

import io.github.jasper.mybatis.encrypt.logsafe.context.MdcContributor;
import io.github.jasper.mybatis.encrypt.logsafe.context.web.TraceContextInterceptor;
import io.github.jasper.mybatis.encrypt.logsafe.context.web.TraceContextWebMvcConfigurer;
import io.github.jasper.mybatis.encrypt.logsafe.context.web.TraceIdResolver;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Tag("unit")
@Tag("config")
class LogsafeContextAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LogsafeContextAutoConfiguration.class));

    @Test
    void shouldAutoConfigureTraceContextBeansByDefault() {
        contextRunner.run(context -> {
            assertNotNull(context.getBean(LogsafeProperties.class));
            assertNotNull(context.getBean(TraceIdResolver.class));
            assertNotNull(context.getBean(MdcContributor.class));
            assertNotNull(context.getBean(TraceContextInterceptor.class));
            assertNotNull(context.getBean(TraceContextWebMvcConfigurer.class));
        });
    }

    @Test
    void shouldBindTypedLogsafeProperties() {
        contextRunner.withPropertyValues(
                        "mybatis.encrypt.logsafe.context.header.trace-id=X-Correlation-Id",
                        "mybatis.encrypt.logsafe.context.header.request-id=X-Request-No",
                        "mybatis.encrypt.logsafe.context.keys.tenant-id-enabled=true",
                        "mybatis.encrypt.logsafe.context.propagation.async-enabled=false")
                .run(context -> {
                    LogsafeProperties properties = context.getBean(LogsafeProperties.class);
                    assertNotNull(properties);
                    org.junit.jupiter.api.Assertions.assertEquals("X-Correlation-Id",
                            properties.getContext().getHeader().getTraceId());
                    org.junit.jupiter.api.Assertions.assertEquals("X-Request-No",
                            properties.getContext().getHeader().getRequestId());
                    org.junit.jupiter.api.Assertions.assertTrue(
                            properties.getContext().getKeys().isTenantIdEnabled());
                    org.junit.jupiter.api.Assertions.assertFalse(
                            properties.getContext().getPropagation().isAsyncEnabled());
                });
    }

    @Test
    void shouldRespectContextEnabledProperty() {
        contextRunner.withPropertyValues("mybatis.encrypt.logsafe.context.enabled=false")
                .run(context -> {
                    assertFalse(context.containsBean("traceContextInterceptor"));
                    assertFalse(context.containsBean("traceContextWebMvcConfigurer"));
                });
    }
}
