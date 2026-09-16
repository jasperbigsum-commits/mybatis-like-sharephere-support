package io.github.jasper.mybatis.encrypt.core.mask;

/**
 * Controller response parsing strategy for sensitive-field replacement.
 *
 * <p>The strategy affects only how the response body is inspected after controller execution. It
 * does not change query rewriting or decryption rules. {@link #RECORDED_ONLY} is the preferred
 * default because recorded MyBatis properties determine which values may be replaced.
 * Annotation-based traversal is available as an explicit fallback for manually assembled response
 * graphs.</p>
 */
public enum SensitiveResponseStrategy {

    /**
     * Masks recorded references and matching property/value copies within the approved DTO graph.
     *
     * <p>Copied DTOs and wrappers require explicit traversal registration unless they declare
     * SensitiveField annotations. Unknown objects are opaque.</p>
     */
    RECORDED_ONLY,

    /**
     * Traverses the approved response DTO graph and masks annotated {@code String} fields.
     *
     * <p>Use this for DTOs assembled outside MyBatis result mapping, for example controller or
     * service level aggregation objects.</p>
     */
    ANNOTATED_FIELDS,

    /**
     * Masks recorded references first, then falls back to annotated object graph traversal.
     *
     * <p>This keeps the precise low-cost path for database-originated values while still covering
     * manually populated fields in the same response.</p>
     */
    RECORDED_THEN_ANNOTATED
}
