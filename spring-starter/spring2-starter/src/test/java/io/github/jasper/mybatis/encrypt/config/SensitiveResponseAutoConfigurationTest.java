package io.github.jasper.mybatis.encrypt.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jasper.mybatis.encrypt.core.lookup.SensitivePlaintextLookupService;
import io.github.jasper.mybatis.encrypt.core.mask.SensitiveDataContext;
import io.github.jasper.mybatis.encrypt.core.mask.SensitiveDataMasker;
import io.github.jasper.mybatis.encrypt.web.SensitiveRequestBodyAdvice;
import io.github.jasper.mybatis.encrypt.web.SensitiveRequestPayloadResolver;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@Tag("unit")
@Tag("config")
@Tag("web")
class SensitiveResponseAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SensitiveResponseAutoConfiguration.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(SensitiveDataMasker.class, SensitiveDataMasker::new)
            .withBean(SensitivePlaintextLookupService.class, () -> new SensitivePlaintextLookupService() {
                @Override
                public String lookup(SensitiveDataContext.SensitiveLookupMeta lookupMeta) {
                    return "plaintext";
                }
            });

    @Test
    void shouldRegisterRequestHydrationBeansWhenLookupServiceExists() {
        contextRunner.run(context -> {
            assertNotNull(context.getBean(SensitiveRequestPayloadResolver.class));
            assertNotNull(context.getBean(SensitiveRequestBodyAdvice.class));
        });
    }
}
