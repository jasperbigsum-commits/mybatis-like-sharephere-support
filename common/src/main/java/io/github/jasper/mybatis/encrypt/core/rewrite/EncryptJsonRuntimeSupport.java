package io.github.jasper.mybatis.encrypt.core.rewrite;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptJsonFieldRule;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptJsonPathRule;
import io.github.jasper.mybatis.encrypt.exception.EncryptionConfigurationException;
import io.github.jasper.mybatis.encrypt.exception.EncryptionErrorCode;
import io.github.jasper.mybatis.encrypt.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON 精确 path 的写入替换与读取回填辅助器。
 *
 * <p>这里不依赖外部 JSON 库，而是内置一个最小 JSON 解析/序列化器，
 * 只处理运行时需要的对象、数组、字符串、数字、布尔和 null。</p>
 */
public final class EncryptJsonRuntimeSupport {

    /**
     * 把命中的 JSON path 明文替换为 hash，并产出独立表写入信息。
     *
     * @param json 原始 JSON
     * @param rule JSON 字段规则
     * @param algorithmRegistry 算法注册中心
     * @return 改写结果
     */
    public EncryptJsonWriteResult rewriteJsonForWrite(String json,
                                                      EncryptJsonFieldRule rule,
                                                      AlgorithmRegistry algorithmRegistry) {
        if (StringUtils.isBlank(json) || rule == null) {
            return new EncryptJsonWriteResult(json, new ArrayList<EncryptJsonWriteResult.PathWrite>());
        }
        Object root = parseJson(json);
        List<EncryptJsonWriteResult.PathWrite> writes = new ArrayList<EncryptJsonWriteResult.PathWrite>();
        for (EncryptJsonPathRule pathRule : rule.pathRules()) {
            PathResolution resolution = resolve(root, pathRule, rule);
            if (resolution == null || resolution.missing()) {
                continue;
            }
            String plainValue = scalarToPlainText(resolution.value(), rule, pathRule);
            if (StringUtils.isBlank(plainValue)) {
                continue;
            }
            String hash = algorithmRegistry.assisted(pathRule.assistedQueryAlgorithm()).transform(plainValue);
            String cipher = algorithmRegistry.cipher(pathRule.cipherAlgorithm()).encrypt(plainValue);
            resolution.replace(hash);
            writes.add(new EncryptJsonWriteResult.PathWrite(pathRule, plainValue, hash, cipher));
        }
        return new EncryptJsonWriteResult(writeJson(root), writes);
    }

    /**
     * 把 JSON 中命中的 hash 值恢复为明文字符串。
     *
     * @param json 当前 JSON
     * @param rule JSON 字段规则
     * @param cipherLookup 密文查找器
     * @param algorithmRegistry 算法注册中心
     * @return 恢复后的 JSON
     */
    public String restoreJsonFromHashes(String json,
                                        EncryptJsonFieldRule rule,
                                        EncryptJsonCipherLookup cipherLookup,
                                        AlgorithmRegistry algorithmRegistry) {
        if (StringUtils.isBlank(json) || rule == null || cipherLookup == null) {
            return json;
        }
        Object root = parseJson(json);
        for (EncryptJsonPathRule pathRule : rule.pathRules()) {
            PathResolution resolution = resolve(root, pathRule, rule);
            if (resolution == null || resolution.missing()) {
                continue;
            }
            Object currentValue = resolution.value();
            if (!(currentValue instanceof String) || StringUtils.isBlank((String) currentValue)) {
                continue;
            }
            String cipher = cipherLookup.findCipher(pathRule, (String) currentValue);
            if (StringUtils.isBlank(cipher)) {
                continue;
            }
            String plainText = algorithmRegistry.cipher(pathRule.cipherAlgorithm()).decrypt(cipher);
            resolution.replace(plainText);
        }
        return writeJson(root);
    }

    private Object parseJson(String json) {
        try {
            JsonParser parser = new JsonParser(json);
            Object value = parser.parseValue();
            parser.ensureDocumentEnd();
            return value;
        } catch (RuntimeException ex) {
            throw new EncryptionConfigurationException(EncryptionErrorCode.INVALID_FIELD_RULE,
                    "Failed to parse JSON field value.", ex);
        }
    }

    private String writeJson(Object value) {
        StringBuilder builder = new StringBuilder();
        writeJsonValue(builder, value);
        return builder.toString();
    }

    private void writeJsonValue(StringBuilder builder, Object value) {
        if (value == null) {
            builder.append("null");
            return;
        }
        if (value instanceof String) {
            builder.append('"').append(escapeJson((String) value)).append('"');
            return;
        }
        if (value instanceof Boolean || value instanceof BigDecimal) {
            builder.append(value.toString());
            return;
        }
        if (value instanceof List<?>) {
            builder.append('[');
            List<?> list = (List<?>) value;
            for (int index = 0; index < list.size(); index++) {
                if (index > 0) {
                    builder.append(',');
                }
                writeJsonValue(builder, list.get(index));
            }
            builder.append(']');
            return;
        }
        if (value instanceof Map<?, ?>) {
            builder.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) {
                    builder.append(',');
                }
                builder.append('"').append(escapeJson(String.valueOf(entry.getKey()))).append('"').append(':');
                writeJsonValue(builder, entry.getValue());
                first = false;
            }
            builder.append('}');
            return;
        }
        throw new EncryptionConfigurationException(EncryptionErrorCode.INVALID_FIELD_RULE,
                "Unsupported JSON value type: " + value.getClass().getName());
    }

    private String escapeJson(String value) {
        StringBuilder builder = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"':
                    builder.append("\\\"");
                    break;
                case '\\':
                    builder.append("\\\\");
                    break;
                case '\b':
                    builder.append("\\b");
                    break;
                case '\f':
                    builder.append("\\f");
                    break;
                case '\n':
                    builder.append("\\n");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                case '\t':
                    builder.append("\\t");
                    break;
                default:
                    if (current < 0x20) {
                        builder.append(String.format("\\u%04x", (int) current));
                    } else {
                        builder.append(current);
                    }
                    break;
            }
        }
        return builder.toString();
    }

    private String scalarToPlainText(Object value,
                                     EncryptJsonFieldRule fieldRule,
                                     EncryptJsonPathRule pathRule) {
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            return (String) value;
        }
        if (value instanceof Boolean || value instanceof BigDecimal) {
            return String.valueOf(value);
        }
        throw new EncryptionConfigurationException(EncryptionErrorCode.INVALID_FIELD_RULE,
                "EncryptJsonPath only supports scalar JSON values. property=" + fieldRule.property()
                        + ", column=" + fieldRule.column()
                        + ", path=" + pathRule.path());
    }

    private PathResolution resolve(Object root, EncryptJsonPathRule pathRule, EncryptJsonFieldRule fieldRule) {
        List<PathSegment> segments = parsePath(pathRule.path(), fieldRule, pathRule);
        if (segments.isEmpty()) {
            return new PathResolution(true, null, null);
        }
        Object current = root;
        for (int index = 0; index < segments.size() - 1; index++) {
            PathSegment segment = segments.get(index);
            if (segment.property != null) {
                if (!(current instanceof Map<?, ?>)) {
                    throw pathTypeMismatch(fieldRule, pathRule);
                }
                Map<?, ?> map = (Map<?, ?>) current;
                if (!map.containsKey(segment.property)) {
                    return new PathResolution(true, null, null);
                }
                current = map.get(segment.property);
            } else {
                if (!(current instanceof List<?>)) {
                    throw pathTypeMismatch(fieldRule, pathRule);
                }
                List<?> list = (List<?>) current;
                if (segment.index < 0 || segment.index >= list.size()) {
                    return new PathResolution(true, null, null);
                }
                current = list.get(segment.index);
            }
        }
        PathSegment leaf = segments.get(segments.size() - 1);
        return new PathResolution(false, current, leaf);
    }

    private EncryptionConfigurationException pathTypeMismatch(EncryptJsonFieldRule fieldRule,
                                                              EncryptJsonPathRule pathRule) {
        return new EncryptionConfigurationException(EncryptionErrorCode.INVALID_FIELD_RULE,
                "EncryptJsonPath structure does not match current JSON value. property=" + fieldRule.property()
                        + ", column=" + fieldRule.column()
                        + ", path=" + pathRule.path());
    }

    private List<PathSegment> parsePath(String path,
                                        EncryptJsonFieldRule fieldRule,
                                        EncryptJsonPathRule pathRule) {
        if (StringUtils.isBlank(path) || path.charAt(0) != '$') {
            throw pathTypeMismatch(fieldRule, pathRule);
        }
        List<PathSegment> segments = new ArrayList<PathSegment>();
        int index = 1;
        while (index < path.length()) {
            char current = path.charAt(index);
            if (current == '.') {
                int start = ++index;
                while (index < path.length()) {
                    char item = path.charAt(index);
                    if (item == '.' || item == '[') {
                        break;
                    }
                    index++;
                }
                segments.add(PathSegment.property(path.substring(start, index)));
                continue;
            }
            if (current == '[') {
                int start = ++index;
                while (index < path.length() && Character.isDigit(path.charAt(index))) {
                    index++;
                }
                int arrayIndex = Integer.parseInt(path.substring(start, index));
                if (index >= path.length() || path.charAt(index) != ']') {
                    throw pathTypeMismatch(fieldRule, pathRule);
                }
                index++;
                segments.add(PathSegment.index(arrayIndex));
                continue;
            }
            throw pathTypeMismatch(fieldRule, pathRule);
        }
        return segments;
    }

    private static final class PathResolution {

        private final boolean missing;
        private final Object parent;
        private final PathSegment leaf;

        private PathResolution(boolean missing, Object parent, PathSegment leaf) {
            this.missing = missing;
            this.parent = parent;
            this.leaf = leaf;
        }

        private boolean missing() {
            return missing;
        }

        private Object value() {
            if (missing || parent == null || leaf == null) {
                return null;
            }
            if (leaf.property != null) {
                return ((Map<?, ?>) parent).get(leaf.property);
            }
            return ((List<?>) parent).get(leaf.index);
        }

        @SuppressWarnings("unchecked")
        private void replace(Object value) {
            if (missing || parent == null || leaf == null) {
                return;
            }
            if (leaf.property != null) {
                ((Map<String, Object>) parent).put(leaf.property, value);
                return;
            }
            ((List<Object>) parent).set(leaf.index, value);
        }
    }

    private static final class PathSegment {

        private final String property;
        private final int index;

        private PathSegment(String property, int index) {
            this.property = property;
            this.index = index;
        }

        private static PathSegment property(String property) {
            return new PathSegment(property, -1);
        }

        private static PathSegment index(int index) {
            return new PathSegment(null, index);
        }
    }

    private static final class JsonParser {

        private final String text;
        private int index;

        private JsonParser(String text) {
            this.text = text;
        }

        private Object parseValue() {
            skipWhitespace();
            if (index >= text.length()) {
                throw new IllegalArgumentException("Unexpected end of JSON");
            }
            char current = text.charAt(index);
            switch (current) {
                case '{':
                    return parseObject();
                case '[':
                    return parseArray();
                case '"':
                    return parseString();
                case 't':
                    return parseTrue();
                case 'f':
                    return parseFalse();
                case 'n':
                    return parseNull();
                default:
                    return parseNumber();
            }
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            index++;
            skipWhitespace();
            if (peek('}')) {
                index++;
                return result;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                result.put(key, parseValue());
                skipWhitespace();
                if (peek('}')) {
                    index++;
                    return result;
                }
                expect(',');
            }
        }

        private List<Object> parseArray() {
            List<Object> result = new ArrayList<Object>();
            index++;
            skipWhitespace();
            if (peek(']')) {
                index++;
                return result;
            }
            while (true) {
                result.add(parseValue());
                skipWhitespace();
                if (peek(']')) {
                    index++;
                    return result;
                }
                expect(',');
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder builder = new StringBuilder();
            while (index < text.length()) {
                char current = text.charAt(index++);
                if (current == '"') {
                    return builder.toString();
                }
                if (current != '\\') {
                    builder.append(current);
                    continue;
                }
                if (index >= text.length()) {
                    throw new IllegalArgumentException("Invalid JSON escape");
                }
                char escape = text.charAt(index++);
                switch (escape) {
                    case '"':
                    case '\\':
                    case '/':
                        builder.append(escape);
                        break;
                    case 'b':
                        builder.append('\b');
                        break;
                    case 'f':
                        builder.append('\f');
                        break;
                    case 'n':
                        builder.append('\n');
                        break;
                    case 'r':
                        builder.append('\r');
                        break;
                    case 't':
                        builder.append('\t');
                        break;
                    case 'u':
                        builder.append((char) Integer.parseInt(text.substring(index, index + 4), 16));
                        index += 4;
                        break;
                    default:
                        throw new IllegalArgumentException("Unsupported JSON escape");
                }
            }
            throw new IllegalArgumentException("Unterminated JSON string");
        }

        private Boolean parseTrue() {
            expectLiteral("true");
            return Boolean.TRUE;
        }

        private Boolean parseFalse() {
            expectLiteral("false");
            return Boolean.FALSE;
        }

        private Object parseNull() {
            expectLiteral("null");
            return null;
        }

        private BigDecimal parseNumber() {
            int start = index;
            if (text.charAt(index) == '-') {
                index++;
            }
            while (index < text.length() && Character.isDigit(text.charAt(index))) {
                index++;
            }
            if (index < text.length() && text.charAt(index) == '.') {
                index++;
                while (index < text.length() && Character.isDigit(text.charAt(index))) {
                    index++;
                }
            }
            if (index < text.length() && (text.charAt(index) == 'e' || text.charAt(index) == 'E')) {
                index++;
                if (index < text.length() && (text.charAt(index) == '+' || text.charAt(index) == '-')) {
                    index++;
                }
                while (index < text.length() && Character.isDigit(text.charAt(index))) {
                    index++;
                }
            }
            return new BigDecimal(text.substring(start, index));
        }

        private void ensureDocumentEnd() {
            skipWhitespace();
            if (index != text.length()) {
                throw new IllegalArgumentException("Unexpected trailing JSON content");
            }
        }

        private void skipWhitespace() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
        }

        private void expect(char expected) {
            skipWhitespace();
            if (index >= text.length() || text.charAt(index) != expected) {
                throw new IllegalArgumentException("Unexpected JSON token");
            }
            index++;
        }

        private void expectLiteral(String literal) {
            if (!text.regionMatches(index, literal, 0, literal.length())) {
                throw new IllegalArgumentException("Unexpected JSON literal");
            }
            index += literal.length();
        }

        private boolean peek(char expected) {
            skipWhitespace();
            return index < text.length() && text.charAt(index) == expected;
        }
    }
}
