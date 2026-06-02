package io.github.jasper.mybatis.encrypt.core.lookup;

import io.github.jasper.mybatis.encrypt.core.mask.SensitiveDataContext;

/**
 * Audit hook for explicit plaintext lookup operations.
 */
public interface SensitivePlaintextAuditRecorder {

    /**
     * Record a successful plaintext lookup.
     *
     * @param lookupMeta lookup meta used for the query
     */
    void recordSuccess(SensitiveDataContext.SensitiveLookupMeta lookupMeta);

    /**
     * Record a failed plaintext lookup.
     *
     * @param lookupMeta lookup meta used for the query
     * @param errorCode stable error code
     */
    void recordFailure(SensitiveDataContext.SensitiveLookupMeta lookupMeta, String errorCode);

    /**
     * No-op recorder.
     *
     * @return no-op recorder
     */
    static SensitivePlaintextAuditRecorder noOp() {
        return new SensitivePlaintextAuditRecorder() {
            @Override
            public void recordSuccess(SensitiveDataContext.SensitiveLookupMeta lookupMeta) {
            }

            @Override
            public void recordFailure(SensitiveDataContext.SensitiveLookupMeta lookupMeta, String errorCode) {
            }
        };
    }
}
