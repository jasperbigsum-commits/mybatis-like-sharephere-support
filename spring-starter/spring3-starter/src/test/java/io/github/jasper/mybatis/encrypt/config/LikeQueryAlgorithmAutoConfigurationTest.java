package io.github.jasper.mybatis.encrypt.config;

import io.github.jasper.mybatis.encrypt.algorithm.LikeQueryAlgorithm;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
@Tag("config")
@Tag("algorithm")
class LikeQueryAlgorithmAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MybatisEncryptionAutoConfiguration.class))
            .withPropertyValues(
                    "mybatis.encrypt.enabled=true",
                    "mybatis.encrypt.default-cipher-key=test-key"
            );

    @Test
    void shouldRegisterBuiltInBusinessLikeAlgorithms() {
        contextRunner.run(context -> {
            assertEquals("110************234",
                    context.getBean("idCardMaskLike", LikeQueryAlgorithm.class).transform("110101199001011234"));
            assertEquals("*******8000",
                    context.getBean("phoneMaskLike", LikeQueryAlgorithm.class).transform("13800138000"));
            assertEquals("***************0123",
                    context.getBean("bankCardMaskLike", LikeQueryAlgorithm.class).transform("6222021234567890123"));
            assertEquals("王*明",
                    context.getBean("nameMaskLike", LikeQueryAlgorithm.class).transform("王小明"));
        });
    }
}
