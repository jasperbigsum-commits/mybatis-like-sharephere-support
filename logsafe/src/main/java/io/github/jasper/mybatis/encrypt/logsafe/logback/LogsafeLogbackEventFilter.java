package io.github.jasper.mybatis.encrypt.logsafe.logback;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;
import io.github.jasper.mybatis.encrypt.logsafe.LogsafeTextMasker;
import io.github.jasper.mybatis.encrypt.logsafe.SemanticHint;
import org.slf4j.helpers.MessageFormatter;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Logback appender filter that masks events immediately before terminal output.
 */
public final class LogsafeLogbackEventFilter extends Filter<ILoggingEvent> {

    private static final Field MESSAGE = field(LoggingEvent.class, "message");
    private static final Field FORMATTED_MESSAGE = field(LoggingEvent.class, "formattedMessage");
    private static final Field ARGUMENT_ARRAY = field(LoggingEvent.class, "argumentArray");
    private static final Field THROWABLE_MESSAGE = field(ThrowableProxy.class, "message");
    private static final Field THROWABLE_OVERRIDING_MESSAGE = field(ThrowableProxy.class, "overridingMessage");
    private static final Method GET_KEY_VALUE_PAIRS = method(LoggingEvent.class, "getKeyValuePairs");
    private static final Method SET_KEY_VALUE_PAIRS = method(LoggingEvent.class, "setKeyValuePairs", List.class);

    private final LogsafeTextMasker textMasker;

    public LogsafeLogbackEventFilter(LogsafeTextMasker textMasker) {
        this.textMasker = Objects.requireNonNull(textMasker, "textMasker");
    }

    @Override
    public FilterReply decide(ILoggingEvent event) {
        if (event instanceof LoggingEvent) {
            try {
                maskEvent((LoggingEvent) event);
            } catch (RuntimeException ex) {
                return FilterReply.NEUTRAL;
            }
        }
        return FilterReply.NEUTRAL;
    }

    private void maskEvent(LoggingEvent event) {
        String renderedMessage = renderMessage(event);
        String maskedMessage = textMasker.mask(renderedMessage);
        if (!Objects.equals(renderedMessage, maskedMessage)) {
            set(MESSAGE, event, maskedMessage);
            set(ARGUMENT_ARRAY, event, null);
            set(FORMATTED_MESSAGE, event, maskedMessage);
        }
        maskKeyValuePairs(event);
        IThrowableProxy throwableProxy = event.getThrowableProxy();
        if (throwableProxy instanceof ThrowableProxy) {
            maskThrowableProxy((ThrowableProxy) throwableProxy);
        }
    }

    private String renderMessage(LoggingEvent event) {
        Object[] argumentArray = event.getArgumentArray();
        String message = event.getMessage();
        if (argumentArray == null || argumentArray.length == 0) {
            return message;
        }
        return MessageFormatter.arrayFormat(message, argumentArray).getMessage();
    }

    private void maskKeyValuePairs(LoggingEvent event) {
        List<?> keyValuePairs = keyValuePairs(event);
        if (keyValuePairs == null || keyValuePairs.isEmpty()) {
            return;
        }
        boolean changed = false;
        List<Object> maskedPairs = new ArrayList<Object>(keyValuePairs.size());
        for (Object pair : keyValuePairs) {
            String key = keyValuePairKey(pair);
            Object value = keyValuePairValue(pair);
            String rawValue = value == null ? null : String.valueOf(value);
            String maskedValue = rawValue == null ? null : textMasker.mask(rawValue, SemanticHint.of(key));
            if (!Objects.equals(rawValue, maskedValue)) {
                changed = true;
            }
            Object maskedPair = newKeyValuePair(pair, key, maskedValue);
            maskedPairs.add(maskedPair == null ? pair : maskedPair);
        }
        if (changed) {
            setKeyValuePairs(event, maskedPairs);
        }
    }

    @SuppressWarnings("unchecked")
    private List<?> keyValuePairs(LoggingEvent event) {
        if (GET_KEY_VALUE_PAIRS == null) {
            return null;
        }
        try {
            return (List<?>) GET_KEY_VALUE_PAIRS.invoke(event);
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }

    private void setKeyValuePairs(LoggingEvent event, List<Object> keyValuePairs) {
        if (SET_KEY_VALUE_PAIRS == null) {
            return;
        }
        try {
            SET_KEY_VALUE_PAIRS.invoke(event, keyValuePairs);
        } catch (ReflectiveOperationException ignore) {
            // Logback 1.2 has no structured key/value API; keep logging alive.
        }
    }

    private String keyValuePairKey(Object pair) {
        Object value = get(field(pair.getClass(), "key"), pair);
        return value == null ? null : String.valueOf(value);
    }

    private Object keyValuePairValue(Object pair) {
        return get(field(pair.getClass(), "value"), pair);
    }

    private Object newKeyValuePair(Object source, String key, Object value) {
        try {
            Constructor<?> constructor = source.getClass().getConstructor(String.class, Object.class);
            return constructor.newInstance(key, value);
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }

    private void maskThrowableProxy(ThrowableProxy throwableProxy) {
        if (throwableProxy == null || throwableProxy.isCyclic()) {
            return;
        }
        maskThrowableField(throwableProxy, THROWABLE_MESSAGE);
        maskThrowableField(throwableProxy, THROWABLE_OVERRIDING_MESSAGE);
        IThrowableProxy cause = throwableProxy.getCause();
        if (cause instanceof ThrowableProxy) {
            maskThrowableProxy((ThrowableProxy) cause);
        }
        IThrowableProxy[] suppressed = throwableProxy.getSuppressed();
        if (suppressed != null) {
            for (IThrowableProxy suppressedProxy : suppressed) {
                if (suppressedProxy instanceof ThrowableProxy) {
                    maskThrowableProxy((ThrowableProxy) suppressedProxy);
                }
            }
        }
    }

    private void maskThrowableField(ThrowableProxy throwableProxy, Field field) {
        if (field == null) {
            return;
        }
        Object value = get(field, throwableProxy);
        if (value instanceof String) {
            String masked = textMasker.mask((String) value);
            if (!Objects.equals(value, masked)) {
                set(field, throwableProxy, masked);
            }
        }
    }

    private static Field field(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException ex) {
            return null;
        }
    }

    private static Method method(Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            Method method = type.getMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException ex) {
            return null;
        }
    }

    private static Object get(Field field, Object target) {
        try {
            return field == null ? null : field.get(target);
        } catch (IllegalAccessException ex) {
            return null;
        }
    }

    private static void set(Field field, Object target, Object value) {
        try {
            if (field != null) {
                field.set(target, value);
            }
        } catch (IllegalAccessException ignore) {
            // Keep logging alive if a Logback version changes its internals.
        }
    }
}
