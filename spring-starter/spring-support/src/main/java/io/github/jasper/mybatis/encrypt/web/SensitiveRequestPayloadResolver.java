package io.github.jasper.mybatis.encrypt.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jasper.mybatis.encrypt.core.lookup.SensitivePlaintextLookupService;
import io.github.jasper.mybatis.encrypt.core.mask.SensitiveDataContext;
import io.github.jasper.mybatis.encrypt.exception.EncryptionException;
import io.github.jasper.mybatis.encrypt.exception.EncryptionErrorCode;
import io.github.jasper.mybatis.encrypt.util.StringUtils;

import java.nio.charset.Charset;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;

/**
 * Rewrites sensitive request payloads before MVC data binding.
 *
 * <p>The preferred request contract is {@code sensitiveSubmitMeta}: unchanged sensitive fields are
 * omitted from the normal form payload and their lookup metadata is sent separately. This resolver
 * restores those omitted fields to plaintext before controller binding. It also accepts the legacy
 * object-shaped sensitive field payload as a compatibility fallback.</p>
 */
public final class SensitiveRequestPayloadResolver {

    private static final String SENSITIVE_SUBMIT_META_KEY = "sensitiveSubmitMeta";

    private final ObjectMapper objectMapper;
    private final SensitivePlaintextLookupService lookupService;

    /**
     * Creates a request payload resolver.
     *
     * @param objectMapper JSON mapper used for request body rewriting
     * @param lookupService plaintext lookup service used through its internal, non-audited path
     */
    public SensitiveRequestPayloadResolver(ObjectMapper objectMapper,
                                           SensitivePlaintextLookupService lookupService) {
        this.objectMapper = objectMapper;
        this.lookupService = lookupService;
    }

    /**
     * Rewrites a JSON request body into the controller DTO shape expected by existing endpoints.
     *
     * <p>The method restores unchanged sensitive fields from {@code sensitiveSubmitMeta} first and
     * then falls back to legacy field-object payloads. The returned JSON no longer contains helper
     * metadata that is not part of the controller DTO.</p>
     *
     * @param body raw JSON request body
     * @param charset request body charset
     * @return rewritten JSON body, or the original body when no rewrite is possible
     */
    public String rewrite(String body, Charset charset) {
        if (StringUtils.isBlank(body) || objectMapper == null || lookupService == null) {
            return body;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root == null || !root.isObject()) {
                return body;
            }
            com.fasterxml.jackson.databind.node.ObjectNode rewritten = objectMapper.createObjectNode();
            Set<String> resolvedByMeta = new LinkedHashSet<>();
            rewriteSubmitMeta(root, rewritten, resolvedByMeta);
            rewriteLegacySensitiveInputs(root, rewritten, resolvedByMeta);
            copyNonSensitiveFields(root, rewritten);
            return objectMapper.writeValueAsString(rewritten);
        } catch (JsonProcessingException ex) {
            throw new EncryptionException(EncryptionErrorCode.GENERAL_FAILURE,
                    "Failed to rewrite sensitive request payload.", ex);
        }
    }

    /**
     * Rewrites an {@code application/x-www-form-urlencoded} body into the controller DTO shape.
     *
     * <p>Supported frontend metadata uses bracketed field names such as
     * {@code sensitiveSubmitMeta[phone][sid]}. Legacy object-mode fields such as
     * {@code phone[lookupMeta][sid]} are accepted as a compatibility fallback.</p>
     *
     * @param body raw form body
     * @param charset form body charset
     * @return rewritten form body, or the original body when no rewrite is possible
     */
    public String rewriteForm(String body, Charset charset) {
        if (StringUtils.isBlank(body) || lookupService == null) {
            return body;
        }
        FormPayload payload = FormPayload.parse(body, charset);
        Set<String> resolvedByMeta = rewriteFormSubmitMeta(payload);
        rewriteFormLegacySensitiveInputs(payload, resolvedByMeta);
        payload.removeByPrefix(SENSITIVE_SUBMIT_META_KEY + "[");
        return payload.toEncodedString(charset);
    }

    private void copyNonSensitiveFields(JsonNode root, com.fasterxml.jackson.databind.node.ObjectNode rewritten) {
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (SENSITIVE_SUBMIT_META_KEY.equals(entry.getKey())) {
                continue;
            }
            if (!rewritten.has(entry.getKey())) {
                rewritten.set(entry.getKey(), entry.getValue());
            }
        }
    }

    private void rewriteSubmitMeta(JsonNode root,
                                   com.fasterxml.jackson.databind.node.ObjectNode rewritten,
                                   Set<String> resolvedByMeta) {
        JsonNode submitMetaNode = root.get(SENSITIVE_SUBMIT_META_KEY);
        if (submitMetaNode == null || !submitMetaNode.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> sensitiveFields = submitMetaNode.fields();
        while (sensitiveFields.hasNext()) {
            Map.Entry<String, JsonNode> entry = sensitiveFields.next();
            SensitiveDataContext.SensitiveLookupMeta lookupMeta = parseLookupMeta(entry.getValue());
            if (lookupMeta == null) {
                continue;
            }
            String plaintext = lookupService.lookupInternal(lookupMeta);
            if (StringUtils.isBlank(plaintext)) {
                continue;
            }
            rewritten.put(entry.getKey(), plaintext);
            resolvedByMeta.add(entry.getKey());
        }
    }

    private void rewriteLegacySensitiveInputs(JsonNode root,
                                             com.fasterxml.jackson.databind.node.ObjectNode rewritten,
                                             Set<String> resolvedByMeta) {
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (SENSITIVE_SUBMIT_META_KEY.equals(entry.getKey())) {
                continue;
            }
            if (resolvedByMeta.contains(entry.getKey())) {
                continue;
            }
            JsonNode valueNode = entry.getValue();
            if (valueNode == null || !valueNode.isObject()) {
                continue;
            }
            String state = text(valueNode.get("state"));
            if (!"masked".equals(state) && !"revealed".equals(state) && !"changed".equals(state)) {
                continue;
            }
            if ("changed".equals(state)) {
                String currentValue = text(valueNode.get("value"));
                if (currentValue != null) {
                    rewritten.put(entry.getKey(), currentValue);
                }
                continue;
            }
            SensitiveDataContext.SensitiveLookupMeta lookupMeta = parseLookupMeta(valueNode.get("lookupMeta"));
            if (lookupMeta == null) {
                continue;
            }
            String plaintext = lookupService.lookupInternal(lookupMeta);
            if (StringUtils.isBlank(plaintext)) {
                continue;
            }
            rewritten.put(entry.getKey(), plaintext);
        }
    }

    private SensitiveDataContext.SensitiveLookupMeta parseLookupMeta(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String sid = text(node.get("sid"));
        String pid = text(node.get("pid"));
        String vid = text(node.get("vid"));
        String hash = text(node.get("hash"));
        if (StringUtils.isBlank(sid) || StringUtils.isBlank(pid)
                || StringUtils.isBlank(vid) || StringUtils.isBlank(hash)) {
            return null;
        }
        return new SensitiveDataContext.SensitiveLookupMeta(sid, pid, vid, hash);
    }

    private String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private Set<String> rewriteFormSubmitMeta(FormPayload payload) {
        Set<String> resolvedByMeta = new LinkedHashSet<String>();
        for (String fieldName : payload.submitMetaFieldNames()) {
            SensitiveDataContext.SensitiveLookupMeta lookupMeta =
                    formLookupMeta(payload, SENSITIVE_SUBMIT_META_KEY + "[" + fieldName + "]");
            if (lookupMeta == null) {
                continue;
            }
            String plaintext = lookupService.lookupInternal(lookupMeta);
            if (StringUtils.isBlank(plaintext)) {
                continue;
            }
            payload.put(fieldName, plaintext);
            resolvedByMeta.add(fieldName);
        }
        return resolvedByMeta;
    }

    private void rewriteFormLegacySensitiveInputs(FormPayload payload, Set<String> resolvedByMeta) {
        for (String fieldName : payload.legacySensitiveFieldNames()) {
            if (resolvedByMeta.contains(fieldName)) {
                payload.removeByPrefix(fieldName + "[");
                continue;
            }
            String state = payload.first(fieldName + "[state]");
            if (!"masked".equals(state) && !"revealed".equals(state) && !"changed".equals(state)) {
                continue;
            }
            if ("changed".equals(state)) {
                String currentValue = payload.first(fieldName + "[value]");
                if (currentValue != null) {
                    payload.put(fieldName, currentValue);
                    payload.removeByPrefix(fieldName + "[");
                }
                continue;
            }
            SensitiveDataContext.SensitiveLookupMeta lookupMeta = formLookupMeta(payload, fieldName + "[lookupMeta]");
            if (lookupMeta == null) {
                continue;
            }
            String plaintext = lookupService.lookupInternal(lookupMeta);
            if (StringUtils.isBlank(plaintext)) {
                continue;
            }
            payload.put(fieldName, plaintext);
            payload.removeByPrefix(fieldName + "[");
        }
    }

    private SensitiveDataContext.SensitiveLookupMeta formLookupMeta(FormPayload payload, String prefix) {
        String sid = payload.first(prefix + "[sid]");
        String pid = payload.first(prefix + "[pid]");
        String vid = payload.first(prefix + "[vid]");
        String hash = payload.first(prefix + "[hash]");
        if (StringUtils.isBlank(sid) || StringUtils.isBlank(pid)
                || StringUtils.isBlank(vid) || StringUtils.isBlank(hash)) {
            return null;
        }
        return new SensitiveDataContext.SensitiveLookupMeta(sid, pid, vid, hash);
    }

    private static final class FormPayload {

        private final LinkedHashMap<String, List<String>> values = new LinkedHashMap<String, List<String>>();

        private static FormPayload parse(String body, Charset charset) {
            FormPayload payload = new FormPayload();
            String[] pairs = body.split("&", -1);
            for (String pair : pairs) {
                if (pair.length() == 0) {
                    continue;
                }
                int separator = pair.indexOf('=');
                String rawName = separator < 0 ? pair : pair.substring(0, separator);
                String rawValue = separator < 0 ? "" : pair.substring(separator + 1);
                payload.add(decode(rawName, charset), decode(rawValue, charset));
            }
            return payload;
        }

        private void add(String name, String value) {
            List<String> list = values.get(name);
            if (list == null) {
                list = new ArrayList<String>();
                values.put(name, list);
            }
            list.add(value);
        }

        private String first(String name) {
            List<String> list = values.get(name);
            return list == null || list.isEmpty() ? null : list.get(0);
        }

        private void put(String name, String value) {
            List<String> list = new ArrayList<String>();
            list.add(value);
            values.put(name, list);
        }

        private void removeByPrefix(String prefix) {
            Iterator<String> names = values.keySet().iterator();
            while (names.hasNext()) {
                if (names.next().startsWith(prefix)) {
                    names.remove();
                }
            }
        }

        private Set<String> submitMetaFieldNames() {
            return bracketFieldNames(SENSITIVE_SUBMIT_META_KEY);
        }

        private Set<String> legacySensitiveFieldNames() {
            Set<String> fieldNames = new LinkedHashSet<String>();
            for (String name : values.keySet()) {
                int bracketIndex = name.indexOf('[');
                if (bracketIndex <= 0) {
                    continue;
                }
                String fieldName = name.substring(0, bracketIndex);
                if (SENSITIVE_SUBMIT_META_KEY.equals(fieldName)) {
                    continue;
                }
                if (values.containsKey(fieldName + "[state]")) {
                    fieldNames.add(fieldName);
                }
            }
            return fieldNames;
        }

        private Set<String> bracketFieldNames(String prefix) {
            Set<String> fieldNames = new LinkedHashSet<String>();
            String marker = prefix + "[";
            for (String name : values.keySet()) {
                if (!name.startsWith(marker)) {
                    continue;
                }
                int start = marker.length();
                int end = name.indexOf(']', start);
                if (end > start) {
                    fieldNames.add(name.substring(start, end));
                }
            }
            return fieldNames;
        }

        private String toEncodedString(Charset charset) {
            StringBuilder builder = new StringBuilder();
            for (Map.Entry<String, List<String>> entry : values.entrySet()) {
                for (String value : entry.getValue()) {
                    if (builder.length() > 0) {
                        builder.append('&');
                    }
                    builder.append(encode(entry.getKey(), charset));
                    builder.append('=');
                    builder.append(encode(value, charset));
                }
            }
            return builder.toString();
        }

        private static String decode(String value, Charset charset) {
            try {
                return URLDecoder.decode(value, charset.name());
            } catch (java.io.UnsupportedEncodingException ex) {
                throw new IllegalArgumentException(ex);
            }
        }

        private static String encode(String value, Charset charset) {
            try {
                return URLEncoder.encode(value == null ? "" : value, charset.name());
            } catch (java.io.UnsupportedEncodingException ex) {
                throw new IllegalArgumentException(ex);
            }
        }
    }

}
