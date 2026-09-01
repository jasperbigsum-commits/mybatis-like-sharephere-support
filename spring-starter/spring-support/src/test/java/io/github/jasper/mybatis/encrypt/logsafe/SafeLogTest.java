package io.github.jasper.mybatis.encrypt.logsafe;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.algorithm.AssistedQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.CipherAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.LikeQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.support.PhoneNumberMaskLikeQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.annotation.SensitiveField;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
@Tag("logsafe")
class SafeLogTest {

    private final SafeLog safeLog = new SafeLog(new LogsafeMasker(new AlgorithmRegistry(
            Collections.<String, CipherAlgorithm>emptyMap(),
            Collections.<String, AssistedQueryAlgorithm>emptyMap(),
            Collections.<String, LikeQueryAlgorithm>singletonMap("phoneMaskLike",
                    new PhoneNumberMaskLikeQueryAlgorithm()))));

    @AfterEach
    void resetSafeLog() {
        SafeLog.reset();
    }

    @Test
    void shouldCloneAndMaskAnnotatedPojo() {
        LoginCommand source = new LoginCommand("13800138000", "P@ssw0rd!");

        Object masked = safeLog.of(source);

        assertTrue(masked.toString().contains("*******8000"));
        assertTrue(masked.toString().contains("password=*********"));
        assertEquals("13800138000", source.getPhone());
        assertEquals("P@ssw0rd!", source.getPassword());
    }

    @Test
    void shouldSupportStaticFacadeWithoutBeanLookup() {
        LoginCommand source = new LoginCommand("13800138000", "P@ssw0rd!");

        Object masked = SafeLog.of(source);

        assertTrue(masked.toString().contains("*******8000"));
        assertFalse(masked.toString().contains("13800138000"));
    }

    @Test
    void shouldMaskNestedMapValuesByKeySemantics() {
        Map<String, Object> profile = new LinkedHashMap<String, Object>();
        profile.put("email", "alice@example.com");
        Map<String, Object> source = new LinkedHashMap<String, Object>();
        source.put("token", "abc123456");
        source.put("profile", profile);

        Object masked = safeLog.of(source);

        assertTrue(masked.toString().contains("token=*********"));
        assertTrue(masked.toString().contains("a****@example.com"));
        assertFalse(masked.toString().contains("abc123456"));
    }

    @Test
    void shouldKeepNonSensitiveValuesReadable() {
        assertEquals("status=SUCCESS", safeLog.kv("status", "SUCCESS").toString());
    }

    static class LoginCommand {

        @SensitiveField(likeAlgorithm = "phoneMaskLike")
        private final String phone;
        private final String password;

        LoginCommand(String phone, String password) {
            this.phone = phone;
            this.password = password;
        }

        public String getPhone() {
            return phone;
        }

        public String getPassword() {
            return password;
        }
    }
}
