package io.github.jasper.mybatis.encrypt.config;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.algorithm.AssistedQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.CipherAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.LikeQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.logsafe.logback.LogsafeLogbackAppenderInstaller;
import java.util.Collections;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Tag("unit")
@Tag("config")
class LogsafeLogbackAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    LogsafeAutoConfiguration.class,
                    LogsafeLogbackAutoConfiguration.class))
            .withBean(AlgorithmRegistry.class, () -> new AlgorithmRegistry(
                    Collections.<String, CipherAlgorithm>emptyMap(),
                    Collections.<String, AssistedQueryAlgorithm>emptyMap(),
                    Collections.<String, LikeQueryAlgorithm>emptyMap()))
            .withPropertyValues(
                    "mybatis.encrypt.enabled=true",
                    "mybatis.encrypt.default-cipher-key=test-key"
            );

    @Test
    void shouldAutoConfigureLogbackAppenderInstallerWhenLogbackIsPresent() {
        contextRunner.run(context ->
                assertNotNull(context.getBean(LogsafeLogbackAppenderInstaller.class)));
    }

    @Test
    void shouldDisableLogbackAppenderInstallerWithTerminalProperty() {
        contextRunner.withPropertyValues("mybatis.encrypt.logsafe.terminal.enabled=false")
                .run(context -> assertFalse(context.containsBean("logsafeLogbackAppenderInstaller")));
    }

    @Test
    void shouldDisableLogbackAppenderInstallerWhenLogsafeIsDisabled() {
        contextRunner.withPropertyValues("mybatis.encrypt.logsafe.enabled=false")
                .run(context -> assertFalse(context.containsBean("logsafeLogbackAppenderInstaller")));
    }
}
