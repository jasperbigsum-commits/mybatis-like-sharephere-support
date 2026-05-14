package io.github.jasper.mybatis.encrypt.logsafe.logback;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.filter.Filter;
import io.github.jasper.mybatis.encrypt.logsafe.LogsafeTextMasker;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * Installs the logsafe terminal filter into Logback appenders when Logback is the active backend.
 */
public final class LogsafeLogbackAppenderInstaller {

    public static final String FILTER_NAME = "mybatis-encrypt-logsafe-terminal-filter";

    private final LogsafeTextMasker textMasker;

    public LogsafeLogbackAppenderInstaller(LogsafeTextMasker textMasker) {
        this.textMasker = Objects.requireNonNull(textMasker, "textMasker");
    }

    /**
     * Installs filters into the active SLF4J Logback context.
     *
     * @return number of appenders updated
     */
    public int install() {
        ILoggerFactory loggerFactory = LoggerFactory.getILoggerFactory();
        if (!(loggerFactory instanceof LoggerContext)) {
            return 0;
        }
        return install((LoggerContext) loggerFactory);
    }

    /**
     * Installs filters into all appenders attached to the supplied context.
     *
     * @param loggerContext Logback context
     * @return number of appenders updated
     */
    public int install(LoggerContext loggerContext) {
        int installed = 0;
        List<Logger> loggers = loggerContext.getLoggerList();
        for (Logger logger : loggers) {
            Iterator<Appender<ILoggingEvent>> appenders = logger.iteratorForAppenders();
            while (appenders.hasNext()) {
                Appender<ILoggingEvent> appender = appenders.next();
                if (install(appender, loggerContext)) {
                    installed++;
                }
            }
        }
        return installed;
    }

    private boolean install(Appender<ILoggingEvent> appender, LoggerContext loggerContext) {
        for (Filter<ILoggingEvent> filter : appender.getCopyOfAttachedFiltersList()) {
            if (FILTER_NAME.equals(filter.getName()) || filter instanceof LogsafeLogbackEventFilter) {
                return false;
            }
        }
        LogsafeLogbackEventFilter filter = new LogsafeLogbackEventFilter(textMasker);
        filter.setName(FILTER_NAME);
        filter.setContext(loggerContext);
        filter.start();
        appender.addFilter(filter);
        return true;
    }
}
