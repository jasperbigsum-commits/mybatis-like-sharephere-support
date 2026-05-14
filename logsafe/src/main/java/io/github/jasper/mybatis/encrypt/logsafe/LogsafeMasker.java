package io.github.jasper.mybatis.encrypt.logsafe;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.annotation.SensitiveField;
import io.github.jasper.mybatis.encrypt.util.ObjectTraversalUtils;
import io.github.jasper.mybatis.encrypt.util.StringUtils;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * Lightweight log masking engine used by {@link SafeLog}.
 *
 * <p>This engine intentionally stays separate from controller-boundary masking. It never touches
 * the runtime response context, never mutates the caller object graph in place, and only depends
 * on the already-registered LIKE masking algorithms plus {@link SensitiveField} metadata.</p>
 */
public final class LogsafeMasker {

    private final AlgorithmRegistry algorithmRegistry;

    /**
     * Creates a log-safe object masker backed by the configured masking algorithms.
     *
     * @param algorithmRegistry registry used to resolve masking algorithms referenced by annotations
     */
    public LogsafeMasker(AlgorithmRegistry algorithmRegistry) {
        this.algorithmRegistry = algorithmRegistry;
    }

    /**
     * Masks an arbitrary value into a detached log-safe representation.
     *
     * @param value source value
     * @return masked value suitable for log rendering
     */
    public Object mask(Object value) {
        return mask(value, null, new IdentityHashMap<>());
    }

    /**
     * Masks an arbitrary value using an optional semantic hint.
     *
     * @param value source value
     * @param hint optional semantic hint
     * @return masked value suitable for log rendering
     */
    public Object mask(Object value, SemanticHint hint) {
        return mask(value, hint, new IdentityHashMap<>());
    }

    /**
     * Masks a single key/value pair and returns a stable `key=value` rendering.
     *
     * @param key semantic key
     * @param value raw value
     * @return masked log text
     */
    public MaskedLogValue maskKeyValue(String key, Object value) {
        Object masked = mask(value, SemanticHint.of(key));
        return new MaskedLogValue(String.valueOf(key) + "=" + String.valueOf(masked));
    }

    private Object mask(Object value, SemanticHint hint, IdentityHashMap<Object, Object> visited) {
        if (value == null) {
            return null;
        }
        if (value instanceof CharSequence) {
            return maskString(String.valueOf(value), hint);
        }
        Class<?> type = value.getClass();
        if (ObjectTraversalUtils.isSimpleValueType(type)) {
            return value;
        }
        if (visited.containsKey(value)) {
            return "[Circular]";
        }
        visited.put(value, Boolean.TRUE);
        if (type.isArray()) {
            return maskArray(value, visited);
        }
        if (value instanceof Collection<?>) {
            return maskCollection((Collection<?>) value, visited);
        }
        if (value instanceof Map<?, ?>) {
            return maskMap((Map<?, ?>) value, visited);
        }
        if (type.getName().startsWith("java.")) {
            return String.valueOf(value);
        }
        return maskPojo(value, visited);
    }

    private List<Object> maskArray(Object array, IdentityHashMap<Object, Object> visited) {
        int length = Array.getLength(array);
        List<Object> masked = new ArrayList<Object>(length);
        for (int index = 0; index < length; index++) {
            masked.add(mask(Array.get(array, index), null, visited));
        }
        return masked;
    }

    private List<Object> maskCollection(Collection<?> values, IdentityHashMap<Object, Object> visited) {
        List<Object> masked = new ArrayList<Object>(values.size());
        for (Object value : values) {
            masked.add(mask(value, null, visited));
        }
        return masked;
    }

    private Map<String, Object> maskMap(Map<?, ?> values, IdentityHashMap<Object, Object> visited) {
        Map<String, Object> masked = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            String key = String.valueOf(entry.getKey());
            masked.put(key, mask(entry.getValue(), SemanticHint.of(key), visited));
        }
        return masked;
    }

    private Object maskPojo(Object value, IdentityHashMap<Object, Object> visited) {
        Map<String, Object> masked = new LinkedHashMap<String, Object>();
        for (Field field : allFields(value.getClass())) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            try {
                Object fieldValue = field.get(value);
                SensitiveField annotation = field.getAnnotation(SensitiveField.class);
                if (annotation != null && fieldValue instanceof String) {
                    masked.put(field.getName(), maskWithSensitiveField((String) fieldValue, field, annotation));
                    continue;
                }
                masked.put(field.getName(), mask(fieldValue, SemanticHint.of(field.getName()), visited));
            } catch (IllegalAccessException ignore) {
                masked.put(field.getName(), "[Inaccessible]");
            }
        }
        return new DetachedLogObject(value.getClass(), masked);
    }

    private String maskWithSensitiveField(String value, Field field, SensitiveField annotation) {
        if (StringUtils.isBlank(value)) {
            return value;
        }
        if (StringUtils.isNotBlank(annotation.likeAlgorithm()) && algorithmRegistry != null) {
            return algorithmRegistry.like(annotation.likeAlgorithm()).transform(value);
        }
        String fieldName = field.getName().toLowerCase(Locale.ROOT);
        if (fieldName.contains("phone") || fieldName.contains("mobile") || fieldName.contains("tel")) {
            return maskPhone(value);
        }
        if (fieldName.contains("email") || fieldName.contains("mail")) {
            return maskEmail(value);
        }
        if (fieldName.contains("idcard") || fieldName.contains("id_card") || fieldName.contains("cert")) {
            return maskKeep(value, 3, 3);
        }
        if (fieldName.contains("bank") || fieldName.contains("card")) {
            return maskKeep(value, 0, 4);
        }
        return maskAll(value);
    }

    private String maskString(String value, SemanticHint hint) {
        if (StringUtils.isBlank(value)) {
            return value;
        }
        String token = hint == null ? null : normalizeToken(hint.primaryToken());
        if (isSecretToken(token)) {
            return maskAll(value);
        }
        if (isPhoneToken(token) || looksLikePhone(value)) {
            return maskPhone(value);
        }
        if (isEmailToken(token) || looksLikeEmail(value)) {
            return maskEmail(value);
        }
        if (isIdCardToken(token) || looksLikeIdCard(value)) {
            return maskKeep(value, 3, 3);
        }
        if (isBankCardToken(token) || looksLikeBankCard(value)) {
            return maskKeep(value, 0, 4);
        }
        return value;
    }

    private String normalizeToken(String token) {
        return token == null ? null : token.toLowerCase(Locale.ROOT);
    }

    private boolean isSecretToken(String token) {
        return containsAny(token, "password", "passwd", "pwd", "token", "secret", "cookie", "authorization");
    }

    private boolean isPhoneToken(String token) {
        return containsAny(token, "phone", "mobile", "tel");
    }

    private boolean isEmailToken(String token) {
        return containsAny(token, "email", "mail");
    }

    private boolean isIdCardToken(String token) {
        return containsAny(token, "idcard", "id_card", "credential", "cert");
    }

    private boolean isBankCardToken(String token) {
        return containsAny(token, "bankcard", "bank_card", "cardno", "card_no");
    }

    private boolean containsAny(String token, String first, String second, String third) {
        return containsAny(token, new String[]{first, second, third});
    }

    private boolean containsAny(String token, String first, String second, String third, String fourth) {
        return containsAny(token, new String[]{first, second, third, fourth});
    }

    private boolean containsAny(String token, String... candidates) {
        if (token == null) {
            return false;
        }
        for (String candidate : candidates) {
            if (token.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private boolean looksLikePhone(String value) {
        String digits = digitsOnly(value);
        return digits.length() >= 7 && digits.length() <= 15;
    }

    private boolean looksLikeEmail(String value) {
        int atIndex = value.indexOf('@');
        return atIndex > 0 && atIndex < value.length() - 1;
    }

    private boolean looksLikeIdCard(String value) {
        String normalized = value.trim();
        return normalized.matches("\\d{15}") || normalized.matches("\\d{17}[0-9Xx]");
    }

    private boolean looksLikeBankCard(String value) {
        String digits = digitsOnly(value);
        return digits.length() >= 12 && digits.length() <= 19;
    }

    private String digitsOnly(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (Character.isDigit(ch)) {
                builder.append(ch);
            }
        }
        return builder.toString();
    }

    private String maskPhone(String value) {
        if (algorithmRegistry != null) {
            try {
                return algorithmRegistry.like("phoneMaskLike").transform(value);
            } catch (RuntimeException ignore) {
                // Fall back to built-in masking when the algorithm is not registered.
            }
        }
        return maskKeep(value, 0, 4);
    }

    private String maskEmail(String value) {
        int at = value.indexOf('@');
        if (at <= 0) {
            return maskKeep(value, 1, 0);
        }
        String local = value.substring(0, at);
        return maskKeep(local, 1, 0) + value.substring(at);
    }

    private String maskKeep(String value, int keepFirst, int keepLast) {
        if (value.length() <= keepFirst + keepLast) {
            return maskAll(value);
        }
        StringBuilder builder = new StringBuilder(value.length());
        if (keepFirst > 0) {
            builder.append(value, 0, keepFirst);
        }
        int maskedLength = value.length() - keepFirst - keepLast;
        for (int index = 0; index < maskedLength; index++) {
            builder.append('*');
        }
        if (keepLast > 0) {
            builder.append(value, value.length() - keepLast, value.length());
        }
        return builder.toString();
    }

    private String maskAll(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            builder.append('*');
        }
        return builder.toString();
    }

    private List<Field> allFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            Field[] declaredFields = current.getDeclaredFields();
            Collections.addAll(fields, declaredFields);
            current = current.getSuperclass();
        }
        return fields;
    }

    private static final class DetachedLogObject {

        private final Class<?> sourceType;
        private final Map<String, Object> fields;

        private DetachedLogObject(Class<?> sourceType, Map<String, Object> fields) {
            this.sourceType = sourceType;
            this.fields = fields;
        }

        @Override
        public String toString() {
            return sourceType.getSimpleName() + fields.toString();
        }
    }
}
