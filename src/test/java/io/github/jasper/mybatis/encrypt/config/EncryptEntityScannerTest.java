package io.github.jasper.mybatis.encrypt.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import lombok.Data;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import io.github.jasper.mybatis.encrypt.annotation.EncryptField;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;

class EncryptEntityScannerTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MybatisEncryptionAutoConfiguration.class))
            .withPropertyValues(
                    "mybatis.encrypt.enabled=true",
                    "mybatis.encrypt.default-cipher-key=test-key",
                    "mybatis.encrypt.scan-entity-annotations=true",
                    "mybatis.encrypt.scan-packages=io.github.jasper.mybatis.encrypt.config"
            );

    @Test
    void shouldRegisterAnnotatedEntityByScanning() {
        contextRunner.run(context -> {
            EncryptMetadataRegistry registry = context.getBean(EncryptMetadataRegistry.class);
            assertTrue(registry.findByEntity(ScannedEntity.class).isPresent());
        });
    }

    @Data
    static class ScannedEntity {

        private Long id;

        @EncryptField(column = "phone", assistedQueryColumn = "phone_hash")
        private String phone;
    }
}
