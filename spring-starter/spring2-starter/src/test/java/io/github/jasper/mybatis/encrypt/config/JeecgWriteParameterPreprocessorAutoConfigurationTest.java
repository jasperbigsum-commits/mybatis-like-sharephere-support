package io.github.jasper.mybatis.encrypt.config;

import io.github.jasper.mybatis.encrypt.plugin.CompositeWriteParameterPreprocessor;
import io.github.jasper.mybatis.encrypt.plugin.WriteParameterPreprocessor;
import org.apache.ibatis.plugin.Interceptor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
@Tag("config")
class JeecgWriteParameterPreprocessorAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MybatisEncryptionAutoConfiguration.class))
            .withPropertyValues(
                    "mybatis.encrypt.enabled=true",
                    "mybatis.encrypt.default-cipher-key=test-key"
            );

    @Test
    void shouldRegisterJeecgPreprocessorWhenJeecgClassesAndBeansExist() {
        contextRunner
                .withUserConfiguration(JeecgBeansConfiguration.class)
                .run(context -> {
                    assertTrue(context.containsBean("jeecgWriteParameterPreprocessor"));
                    assertNotNull(context.getBean("jeecgWriteParameterPreprocessor", WriteParameterPreprocessor.class));
                    assertTrue(context.getBean(WriteParameterPreprocessor.class) instanceof CompositeWriteParameterPreprocessor);
                });
    }

    @Test
    void shouldNotRegisterJeecgPreprocessorWhenJeecgClassesAreHidden() {
        contextRunner
                .withClassLoader(new org.springframework.boot.test.context.FilteredClassLoader("org.jeecg.config.mybatis"))
                .run(context -> {
                    assertFalse(context.containsBean("jeecgWriteParameterPreprocessor"));
                    assertTrue(context.getBean(WriteParameterPreprocessor.class) instanceof CompositeWriteParameterPreprocessor);
                });
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class JeecgBeansConfiguration {

        @org.springframework.context.annotation.Bean
        Interceptor mybatisInterceptor() {
            return new org.jeecg.config.mybatis.MybatisInterceptor();
        }
    }
}
