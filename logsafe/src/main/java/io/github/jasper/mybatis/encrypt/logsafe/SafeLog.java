package io.github.jasper.mybatis.encrypt.logsafe;

import java.util.Objects;

/**
 * Entry-point API for log-safe rendering.
 *
 * <p>Callers hand values to this component before passing them to SLF4J. The static methods are
 * the preferred application-facing API so business code can call {@code SafeLog.of(value)} directly
 * without looking up a Spring bean. Spring auto-configuration installs an application-aware
 * {@link LogsafeMasker}; without Spring, a built-in fallback masker is still available.</p>
 *
 * <p>The returned objects keep their useful structure for debugging, but their
 * {@link Object#toString()} is safe to print.</p>
 */
public final class SafeLog {

    private static final LogsafeMasker FALLBACK_MASKER = new LogsafeMasker(null);
    private static volatile LogsafeMasker masker = FALLBACK_MASKER;

    /**
     * Creates a SafeLog handle and installs the supplied masker as the process-wide default.
     *
     * <p>This constructor is kept for Spring bean compatibility. Business code should normally use
     * {@link #of(Object)}, {@link #of(Object, SemanticHint)}, or {@link #kv(String, Object)}
     * directly.</p>
     *
     * @param masker log-safe masking engine
     */
    public SafeLog(LogsafeMasker masker) {
        use(masker);
    }

    /**
     * Installs the masker used by static log-safe rendering methods.
     *
     * @param masker log-safe masking engine
     */
    public static void use(LogsafeMasker masker) {
        SafeLog.masker = Objects.requireNonNull(masker, "masker");
    }

    /**
     * Restores the built-in fallback masker.
     */
    public static void reset() {
        SafeLog.masker = FALLBACK_MASKER;
    }

    /**
     * Masks an arbitrary object for log output.
     *
     * @param value source value
     * @return detached masked representation
     */
    public static Object of(Object value) {
        return masker.mask(value);
    }

    /**
     * Masks an arbitrary object with an explicit semantic hint.
     *
     * @param value source value
     * @param hint optional semantic hint
     * @return detached masked representation
     */
    public static Object of(Object value, SemanticHint hint) {
        return masker.mask(value, hint);
    }

    /**
     * Masks a single key/value pair and returns a stable string-like wrapper for SLF4J output.
     *
     * @param key semantic key
     * @param value source value
     * @return masked `key=value` wrapper
     */
    public static MaskedLogValue kv(String key, Object value) {
        return masker.maskKeyValue(key, value);
    }
}
