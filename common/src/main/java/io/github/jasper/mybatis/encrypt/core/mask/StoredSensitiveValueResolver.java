package io.github.jasper.mybatis.encrypt.core.mask;

import java.util.Collection;
import java.util.Map;

/**
 * Resolves storage-layer masked values for decrypted response fields.
 *
 * <p>Implementations bridge the response layer and the persistence layer. They receive records
 * captured by {@link SensitiveDataContext} after result decryption and may use each record's
 * encryption rule to load a precomputed masked value from the database. Returned values must already
 * be safe for response output; {@link SensitiveDataMasker} applies them directly without masking a
 * second time.</p>
 *
 * <p>Resolvers should be best-effort: missing rows should simply be absent from the returned map so
 * the caller can fall back to algorithm or annotation masking.</p>
 */
public interface StoredSensitiveValueResolver {

    /**
     * Resolves stored masked values for recorded decrypted fields.
     *
     * @param records recorded decrypted fields from the current response scope
     * @return resolved masked values keyed by the original record; unresolved records should be omitted
     */
    Map<SensitiveDataContext.SensitiveRecord, String> resolve(Collection<SensitiveDataContext.SensitiveRecord> records);
}
