package io.github.jasper.mybatis.encrypt.config;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.algorithm.AssistedQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.CipherAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.LikeQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.support.PhoneNumberMaskLikeQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.annotation.SensitiveField;
import io.github.jasper.mybatis.encrypt.logsafe.LogsafeMasker;
import io.github.jasper.mybatis.encrypt.logsafe.LogsafeTextMasker;
import io.github.jasper.mybatis.encrypt.logsafe.SafeLog;
import io.github.jasper.mybatis.encrypt.logsafe.SemanticHint;
import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
@Tag("config")
class LogsafeAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LogsafeAutoConfiguration.class))
            .withBean(AlgorithmRegistry.class, LogsafeAutoConfigurationTest::algorithmRegistry)
            .withPropertyValues(
                    "mybatis.encrypt.enabled=true",
                    "mybatis.encrypt.default-cipher-key=test-key"
            );

    @AfterEach
    void resetSafeLog() {
        SafeLog.reset();
    }

    @Test
    void shouldAutoConfigureSafeLogAndMaskAnnotatedObjectWithoutMutatingOriginal() {
        contextRunner.run(context -> {
            SafeLog safeLog = context.getBean(SafeLog.class);
            MaskedPhoneLogView view = new MaskedPhoneLogView("13800138000", "ok");

            Object masked = safeLog.of(view);

            assertNotNull(masked);
            assertEquals("13800138000", view.getPhone());
            assertTrue(masked.toString().contains("*******8000"));
            assertTrue(masked.toString().contains("note=ok"));
            assertFalse(masked.toString().contains("13800138000"));
        });
    }

    @Test
    void shouldInstallConfiguredMaskerForStaticFacade() {
        contextRunner.run(context -> {
            assertNotNull(context.getBean(SafeLog.class));

            Object masked = SafeLog.of("13800138000", SemanticHint.of("phone"));

            assertEquals("*******8000", masked.toString());
        });
    }

    @Test
    void shouldMaskStringByExplicitSemanticHint() {
        contextRunner.run(context -> {
            SafeLog safeLog = context.getBean(SafeLog.class);

            Object masked = safeLog.of("13800138000", SemanticHint.of("phone"));

            assertEquals("*******8000", masked.toString());
        });
    }

    @Test
    void shouldMaskKeyValueByFallbackSemanticRules() {
        contextRunner.run(context -> {
            SafeLog safeLog = context.getBean(SafeLog.class);

            Object masked = safeLog.kv("password", "P@ssw0rd!");
            Object phone = safeLog.kv("phone", "13800138000");

            assertEquals("password=*********", masked.toString());
            assertEquals("phone=*******8000", phone.toString());
        });
    }

    @Test
    void shouldAutoConfigureLogsafeTextMasker() {
        contextRunner.run(context -> {
            LogsafeTextMasker textMasker = context.getBean(LogsafeTextMasker.class);

            String masked = textMasker.mask("third-party token=abc123456 phone=13800138000");

            assertTrue(masked.contains("token=*********"));
            assertTrue(masked.contains("phone=*******8000"));
            assertFalse(masked.contains("abc123456"));
            assertFalse(masked.contains("13800138000"));
        });
    }

    @Test
    void shouldRespectCustomLogsafeTextMasker() {
        LogsafeTextMasker custom = new LogsafeTextMasker(new LogsafeMasker(null));

        contextRunner.withBean(LogsafeTextMasker.class, () -> custom)
                .run(context -> assertEquals(custom, context.getBean(LogsafeTextMasker.class)));
    }

    @Test
    void shouldRespectLogsafeEnabledProperty() {
        contextRunner.withPropertyValues("mybatis.encrypt.logsafe.enabled=false")
                .run(context -> {
                    assertFalse(context.containsBean("safeLog"));
                    assertFalse(context.containsBean("logsafeTextMasker"));
                });
    }

    static class MaskedPhoneLogView {

        @SensitiveField(likeAlgorithm = "phoneMaskLike")
        private final String phone;
        private final String note;

        MaskedPhoneLogView(String phone, String note) {
            this.phone = phone;
            this.note = note;
        }

        public String getPhone() {
            return phone;
        }

        public String getNote() {
            return note;
        }
    }

    private static AlgorithmRegistry algorithmRegistry() {
        return new AlgorithmRegistry(
                Collections.<String, CipherAlgorithm>emptyMap(),
                Collections.<String, AssistedQueryAlgorithm>emptyMap(),
                Collections.<String, LikeQueryAlgorithm>singletonMap("phoneMaskLike",
                        new PhoneNumberMaskLikeQueryAlgorithm()));
    }
}
