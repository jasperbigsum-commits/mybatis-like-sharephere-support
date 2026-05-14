package io.github.jasper.mybatis.encrypt.logsafe.logback;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.jasper.mybatis.encrypt.logsafe.LogsafeMasker;
import io.github.jasper.mybatis.encrypt.logsafe.LogsafeTextMasker;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
@Tag("logsafe")
class LogsafeLogbackAppenderInstallerTest {

    @Test
    void shouldInstallFilterIntoExistingAppendersOnlyOnce() {
        LoggerContext context = new LoggerContext();
        Logger logger = context.getLogger("logsafe-installer-test");
        ListAppender<ILoggingEvent> appender = new ListAppender<ILoggingEvent>();
        appender.setName("list");
        appender.setContext(context);
        appender.start();
        logger.addAppender(appender);
        LogsafeLogbackAppenderInstaller installer = new LogsafeLogbackAppenderInstaller(
                new LogsafeTextMasker(new LogsafeMasker(null)));

        assertEquals(1, installer.install(context));
        assertEquals(0, installer.install(context));
        assertTrue(hasAttachedFilters(appender));

        assertEquals(1, appender.getCopyOfAttachedFiltersList().size());
        assertTrue(appender.getCopyOfAttachedFiltersList().get(0) instanceof LogsafeLogbackEventFilter);
    }

    private boolean hasAttachedFilters(ListAppender<ILoggingEvent> appender) {
        try {
            Method method = appender.getClass().getMethod("getCopyOfAttachedFiltersList");
            Object result = method.invoke(appender);
            return result != null;
        } catch (ReflectiveOperationException ex) {
            return false;
        }
    }
}
