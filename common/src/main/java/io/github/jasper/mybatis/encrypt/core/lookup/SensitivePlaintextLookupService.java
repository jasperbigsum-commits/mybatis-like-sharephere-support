package io.github.jasper.mybatis.encrypt.core.lookup;

import io.github.jasper.mybatis.encrypt.core.mask.SensitiveDataContext;

import java.util.Map;

/**
 * Explicit service for looking up plaintext values from response lookup metadata.
 *
 * <p>{@link #lookup(SensitiveDataContext.SensitiveLookupMeta)} is the audited entry point intended
 * for business-side explicit plaintext retrieval. {@link #lookupInternal(SensitiveDataContext.SensitiveLookupMeta)}
 * is the framework-facing hydration path used to restore request DTO fields before controller
 * binding.</p>
 */
public interface SensitivePlaintextLookupService {

    /**
     * Look up plaintext from a response lookup meta payload.
     *
     * @param lookupMeta response lookup meta
     * @return resolved plaintext
     */
    String lookup(SensitiveDataContext.SensitiveLookupMeta lookupMeta);

    /**
     * Look up plaintext from a response lookup meta payload with caller-provided audit attributes.
     *
     * @param lookupMeta response lookup meta
     * @param attributes caller-provided audit attributes
     * @return resolved plaintext
     */
    default String lookup(SensitiveDataContext.SensitiveLookupMeta lookupMeta, Map<String, Object> attributes) {
        return lookup(lookupMeta);
    }

    /**
     * Look up plaintext for internal framework hydration.
     *
     * <p>The default implementation delegates to {@link #lookup(SensitiveDataContext.SensitiveLookupMeta)}
     * so existing custom implementations continue to work unchanged. The built-in default service
     * overrides this method to bypass plaintext lookup auditing when the framework restores request
     * payloads. Custom implementations that record audit events inside {@code lookup(...)} should
     * also override this method to keep request hydration non-audited.</p>
     *
     * @param lookupMeta response lookup meta
     * @return resolved plaintext
     */
    default String lookupInternal(SensitiveDataContext.SensitiveLookupMeta lookupMeta) {
        return lookup(lookupMeta);
    }
}
