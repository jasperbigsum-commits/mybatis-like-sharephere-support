package io.github.jasper.mybatis.encrypt.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import lombok.Data;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import io.github.jasper.mybatis.encrypt.annotation.EncryptField;

@Tag("unit")
@Tag("metadata")
class EncryptEntityScannerTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MybatisEncryptionAutoConfiguration.class))
            .withPropertyValues(
                    "mybatis.encrypt.enabled=true",
                    "mybatis.encrypt.default-cipher-key=test-key",
                    "mybatis.encrypt.scan-entity-annotations=true",
                    "mybatis.encrypt.scan-packages=io.github.jasper.mybatis.encrypt.config"
            );

    /**
     * 测试目的：验证配置扫描和内置算法自动注册符合 starter 默认行为。
     * 测试场景：构造扫描包、实体类型或算法配置，断言注册结果和默认 Bean 与预期一致。
     */
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
