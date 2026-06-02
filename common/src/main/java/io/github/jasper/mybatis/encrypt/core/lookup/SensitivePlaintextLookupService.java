package io.github.jasper.mybatis.encrypt.core.lookup;

import io.github.jasper.mybatis.encrypt.core.mask.SensitiveDataContext;

/**
 * Explicit service for looking up plaintext values from response lookup metadata.
 */
public interface SensitivePlaintextLookupService {

    /**
     * Look up plaintext from a response lookup meta payload.
     *
     * @param lookupMeta response lookup meta
     * @return resolved plaintext
     */
    String lookup(SensitiveDataContext.SensitiveLookupMeta lookupMeta);
}
