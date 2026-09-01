package io.github.jasper.mybatis.encrypt.logsafe.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.spi.FilterReply;
import io.github.jasper.mybatis.encrypt.logsafe.LogsafeMasker;
import io.github.jasper.mybatis.encrypt.logsafe.LogsafeTextMasker;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@Tag("unit")
@Tag("logsafe")
class LogsafeLogbackEventFilterTest {

    private final LogsafeLogbackEventFilter filter =
            new LogsafeLogbackEventFilter(new LogsafeTextMasker(new LogsafeMasker(null)));

    @Test
    void shouldMaskFormattedMessageBeforeAppenderOutput() {
        LoggingEvent event = event("login failed password={} phone={}", null, "P@ssw0rd!", "13800138000");

        assertEquals(FilterReply.NEUTRAL, filter.decide(event));

        assertEquals("login failed password=********* phone=*******8000", event.getFormattedMessage());
        assertFalse(event.getFormattedMessage().contains("P@ssw0rd!"));
        assertFalse(event.getFormattedMessage().contains("13800138000"));
    }

    @Test
    void shouldMaskThrowableProxyMessageWithoutChangingExceptionClass() {
        IllegalStateException exception = new IllegalStateException("token=abc123456");
        LoggingEvent event = event("third-party failed", exception);

        filter.decide(event);

        assertEquals(IllegalStateException.class.getName(), event.getThrowableProxy().getClassName());
        assertEquals("token=*********", event.getThrowableProxy().getMessage());
        assertFalse(event.getThrowableProxy().getMessage().contains("abc123456"));
    }

    @Test
    void shouldMaskThrowableProxyFieldsByReflection() throws Exception {
        IllegalStateException exception = new IllegalStateException("password=abc123456");
        LoggingEvent event = event("third-party failed", exception);

        filter.decide(event);

        Object proxy = event.getThrowableProxy();
        Field message = proxy.getClass().getDeclaredField("message");
        message.setAccessible(true);
        assertEquals("password=*********", message.get(proxy));
    }

    private LoggingEvent event(String message, Throwable throwable, Object... arguments) {
        LoggerContext context = new LoggerContext();
        Logger logger = context.getLogger("logsafe-test");
        return new LoggingEvent(getClass().getName(), logger, Level.INFO, message, throwable, arguments);
    }
}
