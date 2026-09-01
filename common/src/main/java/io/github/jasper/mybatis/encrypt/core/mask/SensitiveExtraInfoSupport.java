package io.github.jasper.mybatis.encrypt.core.mask;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Response DTO base type that can carry best-effort sensitive lookup metadata.
 */
public abstract class SensitiveExtraInfoSupport {

    private final Map<String, SensitiveDataContext.SensitiveLookupMeta> sensitiveLookupMeta =
            new LinkedHashMap<String, SensitiveDataContext.SensitiveLookupMeta>();

    /**
     * Returns response lookup metadata keyed by property name.
     *
     * @return lookup metadata map
     */
    public Map<String, SensitiveDataContext.SensitiveLookupMeta> getSensitiveLookupMeta() {
        return sensitiveLookupMeta.isEmpty() ? null : sensitiveLookupMeta;
    }

    /**
     * Returns the mutable storage map used by runtime masking.
     *
     * @return mutable lookup metadata map
     */
    Map<String, SensitiveDataContext.SensitiveLookupMeta> sensitiveLookupMetaStorage() {
        return sensitiveLookupMeta;
    }
}
