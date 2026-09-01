package io.github.jasper.mybatis.encrypt.logsafe;

import io.github.jasper.mybatis.encrypt.util.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Terminal text masker for log lines, third-party messages, and exception text.
 *
 * <p>This component is deliberately framework-agnostic. It does not install appenders, filters,
 * layouts, or exception handlers; callers can wire it into their own logging or reporting boundary.
 * The implementation keeps matching conservative: keyed sensitive fragments are masked by semantic
 * key, while unkeyed fragments are masked only when they match high-confidence sensitive formats.</p>
 */
public final class LogsafeTextMasker {

    private static final String SENSITIVE_KEY =
            "(?:password|passwd|pwd|token|secret|cookie|authorization|phone|mobile|tel|email|mail"
                    + "|idcard|id_card|credential|cert|bankcard|bank_card|cardno|card_no)";

    private static final Pattern JSON_VALUE = Pattern.compile(
            "(\"[^\"]*" + SENSITIVE_KEY + "[^\"]*\"\\s*:\\s*\")([^\"\\\\]*)(\")",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AUTHORIZATION_VALUE = Pattern.compile(
            "(\\bauthorization\\b\\s*[:=]\\s*)([^,;\\r\\n}\\]]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern KEY_VALUE = Pattern.compile(
            "(\\b([A-Za-z0-9_.-]*" + SENSITIVE_KEY + "[A-Za-z0-9_.-]*)\\b\\s*[:=]\\s*)(\"?)([^\\s\",;}\\]]+)(\"?)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL = Pattern.compile(
            "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");
    private static final Pattern ID_CARD = Pattern.compile(
            "\\b(?:\\d{15}|\\d{17}[0-9Xx])\\b");
    private static final Pattern BANK_CARD = Pattern.compile(
            "\\b\\d{12,19}\\b");
    private static final Pattern PHONE = Pattern.compile(
            "(?<!\\d)(?:\\+?\\d{1,3}[- ]?)?1[3-9]\\d{9}(?!\\d)");

    private final LogsafeMasker masker;

    /**
     * Creates a terminal text masker.
     *
     * @param masker value-level log-safe masking engine
     */
    public LogsafeTextMasker(LogsafeMasker masker) {
        this.masker = masker;
    }

    /**
     * Masks a log line or message using keyed fragments and high-confidence value patterns.
     *
     * @param message raw log text
     * @return masked log text
     */
    public String mask(String message) {
        return mask(message, null);
    }

    /**
     * Masks a log line or message with an optional semantic hint.
     *
     * @param message raw log text
     * @param hint optional semantic hint for the whole message
     * @return masked log text
     */
    public String mask(String message, SemanticHint hint) {
        if (StringUtils.isBlank(message)) {
            return message;
        }
        String masked = maskKeyedFragments(message);
        masked = maskPattern(masked, EMAIL, "email");
        masked = maskPattern(masked, ID_CARD, "idCard");
        masked = maskPattern(masked, BANK_CARD, "bankCard");
        masked = maskPattern(masked, PHONE, "phone");
        if (masked.equals(message) && hint != null) {
            return maskValue(message, hint);
        }
        return masked;
    }

    /**
     * Creates a detached throwable whose messages are safe for log output.
     *
     * <p>The original throwable is never mutated. Causes and suppressed exceptions are copied as
     * masked wrappers so printing the returned throwable does not expose the original messages.</p>
     *
     * @param throwable source throwable
     * @return detached throwable with masked messages, or {@code null}
     */
    public Throwable mask(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        return maskThrowable(throwable);
    }

    private String maskKeyedFragments(String message) {
        String masked = replaceJsonValues(message);
        masked = replaceAuthorizationValues(masked);
        return replaceKeyValues(masked);
    }

    private String replaceJsonValues(String message) {
        Matcher matcher = JSON_VALUE.matcher(message);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String key = stripQuotes(matcher.group(1));
            String replacement = matcher.group(1) + maskValue(matcher.group(2), SemanticHint.of(key)) + matcher.group(3);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String replaceAuthorizationValues(String message) {
        Matcher matcher = AUTHORIZATION_VALUE.matcher(message);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String replacement = matcher.group(1) + maskValue(matcher.group(2).trim(), SemanticHint.of("authorization"));
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String replaceKeyValues(String message) {
        Matcher matcher = KEY_VALUE.matcher(message);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(2);
            String replacement = matcher.group(1) + matcher.group(3)
                    + maskValue(matcher.group(4), SemanticHint.of(key)) + matcher.group(5);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String maskPattern(String message, Pattern pattern, String hint) {
        Matcher matcher = pattern.matcher(message);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String replacement = maskValue(matcher.group(), SemanticHint.of(hint));
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private Throwable maskThrowable(Throwable throwable) {
        MaskedThrowable masked = new MaskedThrowable(
                throwable.getClass().getName(),
                mask(throwable.getMessage()),
                throwable.getCause() == null ? null : maskThrowable(throwable.getCause()));
        masked.setStackTrace(throwable.getStackTrace());
        for (Throwable suppressed : throwable.getSuppressed()) {
            masked.addSuppressed(maskThrowable(suppressed));
        }
        return masked;
    }

    private String maskValue(String value, SemanticHint hint) {
        if (masker == null) {
            return value;
        }
        Object masked = masker.mask(value, hint);
        return String.valueOf(masked);
    }

    private String stripQuotes(String keyPrefix) {
        int start = keyPrefix.indexOf('"');
        int end = keyPrefix.indexOf('"', start + 1);
        if (start >= 0 && end > start) {
            return keyPrefix.substring(start + 1, end);
        }
        return keyPrefix;
    }

    private static final class MaskedThrowable extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String sourceType;

        private MaskedThrowable(String sourceType, String message, Throwable cause) {
            super(message, cause);
            this.sourceType = sourceType;
        }

        @Override
        public String toString() {
            String message = getLocalizedMessage();
            return message == null ? sourceType : sourceType + ": " + message;
        }
    }
}
