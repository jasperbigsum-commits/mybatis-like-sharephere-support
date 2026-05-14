package io.github.jasper.mybatis.encrypt.config;

import io.github.jasper.mybatis.encrypt.logsafe.context.async.MdcTaskDecorator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.task.TaskDecorator;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Tag("unit")
@Tag("config")
class LogsafeAsyncContextAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LogsafeAsyncContextAutoConfiguration.class));

    @Test
    void shouldAutoConfigureMdcTaskDecoratorByDefault() {
        contextRunner.run(context -> {
            assertNotNull(context.getBean(LogsafeProperties.class));
            assertNotNull(context.getBean(TaskDecorator.class));
            assertNotNull(context.getBean(MdcTaskDecorator.class));
        });
    }

    @Test
    void shouldBindAsyncTypedProperties() {
        contextRunner.withPropertyValues("mybatis.encrypt.logsafe.context.propagation.async-enabled=true")
                .run(context -> {
                    LogsafeProperties properties = context.getBean(LogsafeProperties.class);
                    assertNotNull(properties);
                    org.junit.jupiter.api.Assertions.assertTrue(
                            properties.getContext().getPropagation().isAsyncEnabled());
                });
    }

    @Test
    void shouldRespectAsyncPropagationEnabledProperty() {
        contextRunner.withPropertyValues("mybatis.encrypt.logsafe.context.propagation.async-enabled=false")
                .run(context -> assertFalse(context.containsBean("mdcTaskDecorator")));
    }
}
