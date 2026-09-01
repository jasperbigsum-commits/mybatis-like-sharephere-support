package io.github.jasper.mybatis.encrypt.logsafe;

import io.github.jasper.mybatis.encrypt.util.StringUtils;

/**
 * Optional semantic hint used by {@link SafeLog} when the caller knows what a value represents.
 *
 * <p>The first implementation keeps the model intentionally small: hints may describe a field
 * name, an object path, or a coarse domain name such as {@code phone} or {@code token}. The
 * logsafe classifier consumes whichever fields are present and falls back to value-pattern
 * detection when no hint is supplied.</p>
 */
public final class SemanticHint {

    private final String fieldName;
    private final String path;
    private final String domainHint;

    private SemanticHint(String fieldName, String path, String domainHint) {
        this.fieldName = fieldName;
        this.path = path;
        this.domainHint = domainHint;
    }

    /**
     * Creates a simple hint whose field name and domain hint share the same token.
     *
     * @param token semantic token such as {@code phone} or {@code password}
     * @return semantic hint
     */
    public static SemanticHint of(String token) {
        return new SemanticHint(token, null, token);
    }

    /**
     * Creates a hint with explicit fields.
     *
     * @param fieldName field name associated with the value, may be {@code null}
     * @param path object path associated with the value, may be {@code null}
     * @param domainHint coarse domain hint associated with the value, may be {@code null}
     * @return semantic hint
     */
    public static SemanticHint of(String fieldName, String path, String domainHint) {
        return new SemanticHint(fieldName, path, domainHint);
    }

    /**
     * Returns the source field name hint.
     *
     * @return field name hint, or {@code null}
     */
    public String fieldName() {
        return fieldName;
    }

    /**
     * Returns the source object path hint.
     *
     * @return path hint, or {@code null}
     */
    public String path() {
        return path;
    }

    /**
     * Returns the coarse semantic domain hint.
     *
     * @return domain hint, or {@code null}
     */
    public String domainHint() {
        return domainHint;
    }

    /**
     * Returns the first non-blank token from field name, path, or domain hint.
     *
     * @return primary semantic token, or {@code null} when none exists
     */
    public String primaryToken() {
        if (StringUtils.isNotBlank(fieldName)) {
            return fieldName;
        }
        if (StringUtils.isNotBlank(path)) {
            return path;
        }
        return StringUtils.isNotBlank(domainHint) ? domainHint : null;
    }
}
