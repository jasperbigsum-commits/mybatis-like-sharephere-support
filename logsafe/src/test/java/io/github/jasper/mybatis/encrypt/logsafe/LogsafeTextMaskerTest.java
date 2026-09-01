package io.github.jasper.mybatis.encrypt.logsafe;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
@Tag("logsafe")
class LogsafeTextMaskerTest {

    private final LogsafeTextMasker masker = new LogsafeTextMasker(new LogsafeMasker(null));

    @Test
    void shouldMaskKeyValueFragments() {
        String masked = masker.mask("login failed password=P@ssw0rd! token:abc123456 phone=13800138000");

        assertTrue(masked.contains("password=*********"));
        assertTrue(masked.contains("token:*********"));
        assertTrue(masked.contains("phone=*******8000"));
        assertFalse(masked.contains("P@ssw0rd!"));
        assertFalse(masked.contains("abc123456"));
        assertFalse(masked.contains("13800138000"));
    }

    @Test
    void shouldMaskJsonStyleValues() {
        String masked = masker.mask("{\"email\":\"alice@example.com\",\"authorization\":\"Bearer abc.def\"}");

        assertTrue(masked.contains("\"email\":\"a****@example.com\""));
        assertTrue(masked.contains("\"authorization\":\"**************\""));
        assertFalse(masked.contains("alice@example.com"));
        assertFalse(masked.contains("Bearer abc.def"));
    }

    @Test
    void shouldMaskHighConfidenceUnkeyedValues() {
        String masked = masker.mask("contact 13800138000 alice@example.com 110101199003071234 6222020202020202");

        assertTrue(masked.contains("*******8000"));
        assertTrue(masked.contains("a****@example.com"));
        assertTrue(masked.contains("110************234"));
        assertTrue(masked.contains("************0202"));
        assertFalse(masked.contains("13800138000"));
        assertFalse(masked.contains("alice@example.com"));
        assertFalse(masked.contains("110101199003071234"));
        assertFalse(masked.contains("6222020202020202"));
    }

    @Test
    void shouldKeepNonSensitiveTextReadable() {
        String message = "order status=SUCCESS amount=128 trace=abc-123";

        assertEquals(message, masker.mask(message));
    }

    @Test
    void shouldHandleNullAndBlankMessages() {
        assertNull(masker.mask((String) null));
        assertEquals("", masker.mask(""));
        assertEquals("   ", masker.mask("   "));
    }

    @Test
    void shouldMaskThrowableWithoutMutatingSource() {
        IllegalStateException source = new IllegalStateException("token=abc123456 phone=13800138000",
                new IllegalArgumentException("email=alice@example.com"));
        source.addSuppressed(new RuntimeException("password=P@ssw0rd!"));

        Throwable masked = masker.mask(source);

        assertNotSame(source, masked);
        assertEquals("token=abc123456 phone=13800138000", source.getMessage());
        assertTrue(masked.toString().contains("token=*********"));
        assertTrue(masked.toString().contains("phone=*******8000"));
        assertTrue(masked.getCause().toString().contains("email=a****@example.com"));
        assertTrue(masked.getSuppressed()[0].toString().contains("password=*********"));
        assertFalse(masked.toString().contains("abc123456"));
        assertFalse(masked.getCause().toString().contains("alice@example.com"));
        assertFalse(masked.getSuppressed()[0].toString().contains("P@ssw0rd!"));
    }
}
