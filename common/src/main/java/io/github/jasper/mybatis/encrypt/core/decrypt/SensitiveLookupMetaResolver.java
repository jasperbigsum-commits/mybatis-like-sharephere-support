package io.github.jasper.mybatis.encrypt.core.decrypt;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.core.mask.SensitiveDataContext.SensitiveLookupMeta;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import io.github.jasper.mybatis.encrypt.util.PropertyValueAccessor;
import io.github.jasper.mybatis.encrypt.util.StringUtils;

/**
 * Best-effort resolver for response lookup metadata captured during result decryption.
 */
public final class SensitiveLookupMetaResolver {

    private final AlgorithmRegistry algorithmRegistry;
    private final PropertyValueAccessor propertyValueAccessor = new PropertyValueAccessor();

    /**
     * Creates a resolver.
     *
     * @param algorithmRegistry algorithm registry used to recompute the lookup hash
     */
    public SensitiveLookupMetaResolver(AlgorithmRegistry algorithmRegistry) {
        this.algorithmRegistry = algorithmRegistry;
    }

    /**
     * Resolves lookup meta without blocking the caller when metadata is incomplete.
     *
     * @param rootOwner root result object
     * @param leafOwner field owner that carries the decrypted property
     * @param rule encryption rule
     * @param decrypted decrypted plaintext value
     * @return lookup meta, or {@code null} when it cannot be resolved
     */
    public SensitiveLookupMeta tryResolve(Object rootOwner,
                                          Object leafOwner,
                                          EncryptColumnRule rule,
                                          String decrypted) {
        if (algorithmRegistry == null || rule == null || StringUtils.isBlank(decrypted)) {
            return null;
        }
        if (StringUtils.isBlank(rule.sidCode()) || StringUtils.isBlank(rule.pidCode())
                || !rule.hasResolvedLookupBusinessKey() || StringUtils.isBlank(rule.assistedQueryAlgorithm())) {
            return null;
        }
        try {
            String businessKeyValue = resolveLookupBusinessKeyValue(rootOwner, leafOwner, rule.lookupBusinessKey());
            if (StringUtils.isBlank(businessKeyValue)) {
                return null;
            }
            String hash = algorithmRegistry.assisted(rule.assistedQueryAlgorithm()).transform(decrypted);
            if (StringUtils.isBlank(hash)) {
                return null;
            }
            return new SensitiveLookupMeta(
                    rule.sidCode(),
                    rule.pidCode(),
                    businessKeyValue,
                    hash
            );
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String resolveLookupBusinessKeyValue(Object rootOwner, Object leafOwner, String lookupBusinessKey) {
        String rootValue = readLookupBusinessKey(rootOwner, lookupBusinessKey);
        if (StringUtils.isNotBlank(rootValue)) {
            return rootValue;
        }
        return readLookupBusinessKey(leafOwner, lookupBusinessKey);
    }

    private String readLookupBusinessKey(Object owner, String lookupBusinessKey) {
        if (owner == null || StringUtils.isBlank(lookupBusinessKey)) {
            return null;
        }
        PropertyValueAccessor.PropertyReference businessKeyReference =
                propertyValueAccessor.resolve(owner, lookupBusinessKey);
        if (businessKeyReference == null) {
            return null;
        }
        Object businessKeyValue = businessKeyReference.getValue();
        if (businessKeyValue == null) {
            return null;
        }
        String value = String.valueOf(businessKeyValue);
        return StringUtils.isBlank(value) ? null : value;
    }
}
