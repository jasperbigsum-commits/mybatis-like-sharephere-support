package io.github.jasper.mybatis.encrypt.config;

import io.github.jasper.mybatis.encrypt.annotation.SensitiveField;
import io.github.jasper.mybatis.encrypt.annotation.SensitiveResponseTrigger;
import io.github.jasper.mybatis.encrypt.core.mask.SensitiveDataContext;
import io.github.jasper.mybatis.encrypt.core.mask.SensitiveResponseStrategy;
import lombok.Data;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@Tag("unit")
@Tag("config")
@Tag("web")
class SensitiveMaskingAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AopAutoConfiguration.class,
                    MybatisEncryptionAutoConfiguration.class,
                    SensitiveMaskingAutoConfiguration.class))
            .withUserConfiguration(TestConfiguration.class)
            .withPropertyValues(
                    "mybatis.encrypt.enabled=true",
                    "mybatis.encrypt.default-cipher-key=test-key"
            );

    /**
     * 测试目的：验证没有 controller 已开脱敏上下文时，方法级触发注解不会自行开启作用域。
     * 测试场景：构造一个被 Spring AOP 代理的 service 方法，仅标注 SensitiveResponseTrigger，
     * 但调用前不打开 SensitiveDataContext，断言其返回 DTO 保持原值且线程中不存在残留作用域。
     */
    @Test
    void shouldKeepServiceMethodReturnValueWhenNoControllerScopeIsActive() {
        contextRunner.run(context -> {
            TriggeredAssembler assembler = context.getBean(TriggeredAssembler.class);
            MaskedPhoneView view = assembler.buildMaskedView();

            assertEquals("13800138000", view.phone);
            assertFalse(SensitiveDataContext.isActive());
        });
    }

    /**
     * 测试目的：验证方法级触发注解只会消费已经存在的脱敏上下文。
     * 测试场景：调用 service 方法前手动打开 controller 等价的 SensitiveDataContext，
     * 断言返回 DTO 会在方法离开前被直接替换，且作用域仍由外层调用者负责关闭。
     */
    @Test
    void shouldMaskServiceMethodReturnValueWhenControllerScopeAlreadyActive() {
        contextRunner.run(context -> {
            TriggeredAssembler assembler = context.getBean(TriggeredAssembler.class);
            try (SensitiveDataContext.Scope ignored =
                         SensitiveDataContext.open(false, SensitiveResponseStrategy.ANNOTATED_FIELDS)) {
                MaskedPhoneView view = assembler.buildMaskedView();

                assertEquals("*******8000", view.phone);
            }
            assertFalse(SensitiveDataContext.isActive());
        });
    }

    @Configuration
    static class TestConfiguration {

        @Bean
        TriggeredAssembler triggeredAssembler() {
            return new TriggeredAssembler();
        }
    }

    static class TriggeredAssembler {

        @SensitiveResponseTrigger
        public MaskedPhoneView buildMaskedView() {
            return new MaskedPhoneView("13800138000");
        }
    }

    @Data
    static class MaskedPhoneView {

        @SensitiveField(likeAlgorithm = "phoneMaskLike")
        private String phone;

        MaskedPhoneView(String phone) {
            this.phone = phone;
        }
    }
}
