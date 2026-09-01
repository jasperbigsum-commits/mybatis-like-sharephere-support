package io.github.jasper.mybatis.encrypt.core.lookup;

import io.github.jasper.mybatis.encrypt.core.mask.SensitiveDataContext;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Structured audit event emitted by explicit plaintext lookup.
 *
 * <p>The event is produced only by the audited
 * {@link SensitivePlaintextLookupService#lookup(io.github.jasper.mybatis.encrypt.core.mask.SensitiveDataContext.SensitiveLookupMeta, java.util.Map)}
 * path. Framework-internal request hydration uses {@code lookupInternal(...)} and must not emit
 * this event.</p>
 *
 * <p>When the lookup metadata can be resolved to an encryption rule, {@code tableName},
 * {@code propertyName}, and {@code columnName} contain the canonical rule fields. If validation or
 * rule resolution fails before a rule is available, those fields remain {@code null} while the raw
 * lookup metadata and error code are still preserved.</p>
 *
 * <p>Successful events include the resolved plaintext. Implementations should treat the whole event
 * as sensitive data: do not write {@link #getPlaintext()} to ordinary application logs, exception
 * messages, or metrics labels. Persist it only when the application's explicit audit policy allows
 * plaintext retention.</p>
 */
public final class SensitivePlaintextAuditEvent {

    private final boolean success;
    private final String tableName;
    private final String propertyName;
    private final String columnName;
    private final SensitiveDataContext.SensitiveLookupMeta lookupMeta;
    private final String plaintext;
    private final String errorCode;
    private final Map<String, Object> attributes;

    private SensitivePlaintextAuditEvent(Builder builder) {
        this.success = builder.success;
        this.tableName = builder.tableName;
        this.propertyName = builder.propertyName;
        this.columnName = builder.columnName;
        this.lookupMeta = builder.lookupMeta;
        this.plaintext = builder.plaintext;
        this.errorCode = builder.errorCode;
        this.attributes = builder.attributes.isEmpty()
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(builder.attributes));
    }

    /**
     * Creates a new audit event builder.
     *
     * @return builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns whether the explicit plaintext lookup succeeded.
     *
     * @return {@code true} for successful lookups, {@code false} for failed lookups
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * Returns the canonical physical table name resolved from {@code sid}.
     *
     * @return table name, or {@code null} when the lookup metadata could not be resolved to a rule
     */
    public String getTableName() {
        return tableName;
    }

    /**
     * Returns the entity property name resolved from {@code pid}.
     *
     * @return property name, or {@code null} when the lookup metadata could not be resolved to a rule
     */
    public String getPropertyName() {
        return propertyName;
    }

    /**
     * Returns the business column name resolved from {@code pid}.
     *
     * <p>For separate-table rules this is the logical main-table column/reference column declared by
     * the field rule, not necessarily the separate-table ciphertext column.</p>
     *
     * @return column name, or {@code null} when the lookup metadata could not be resolved to a rule
     */
    public String getColumnName() {
        return columnName;
    }

    /**
     * Returns the raw lookup metadata supplied by the caller.
     *
     * @return lookup metadata; may be incomplete when validation failed
     */
    public SensitiveDataContext.SensitiveLookupMeta getLookupMeta() {
        return lookupMeta;
    }

    /**
     * Returns the resolved plaintext for successful lookups.
     *
     * <p>This value is intentionally present so audit implementations can apply their own retention
     * policy. Treat it as sensitive data and avoid ordinary logging.</p>
     *
     * @return plaintext on success, or {@code null} on failure
     */
    public String getPlaintext() {
        return plaintext;
    }

    /**
     * Returns the stable error code for failed lookups.
     *
     * @return error code on failure, or {@code null} on success
     */
    public String getErrorCode() {
        return errorCode;
    }

    /**
     * Returns caller-provided audit attributes.
     *
     * <p>These values come from {@link SensitivePlaintextLookupService#lookup(io.github.jasper.mybatis.encrypt.core.mask.SensitiveDataContext.SensitiveLookupMeta, java.util.Map)}
     * overloads that accept
     * an attributes map. Typical values include operator id, tenant id, ticket number, approval
     * source, or trace id. The returned map is immutable.</p>
     *
     * @return immutable custom attributes, never {@code null}
     */
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    /**
     * Builder for {@link SensitivePlaintextAuditEvent}.
     */
    public static final class Builder {

        private boolean success;
        private String tableName;
        private String propertyName;
        private String columnName;
        private SensitiveDataContext.SensitiveLookupMeta lookupMeta;
        private String plaintext;
        private String errorCode;
        private final Map<String, Object> attributes = new LinkedHashMap<String, Object>();

        private Builder() {
        }

        /**
         * Sets whether the lookup succeeded.
         *
         * @param success success flag
         * @return this builder
         */
        public Builder success(boolean success) {
            this.success = success;
            return this;
        }

        /**
         * Sets the canonical physical table name resolved from {@code sid}.
         *
         * @param tableName table name
         * @return this builder
         */
        public Builder tableName(String tableName) {
            this.tableName = tableName;
            return this;
        }

        /**
         * Sets the entity property name resolved from {@code pid}.
         *
         * @param propertyName property name
         * @return this builder
         */
        public Builder propertyName(String propertyName) {
            this.propertyName = propertyName;
            return this;
        }

        /**
         * Sets the business column name resolved from {@code pid}.
         *
         * @param columnName column name
         * @return this builder
         */
        public Builder columnName(String columnName) {
            this.columnName = columnName;
            return this;
        }

        /**
         * Sets the raw lookup metadata supplied by the caller.
         *
         * @param lookupMeta lookup metadata
         * @return this builder
         */
        public Builder lookupMeta(SensitiveDataContext.SensitiveLookupMeta lookupMeta) {
            this.lookupMeta = lookupMeta;
            return this;
        }

        /**
         * Sets the resolved plaintext for a successful lookup.
         *
         * @param plaintext plaintext value
         * @return this builder
         */
        public Builder plaintext(String plaintext) {
            this.plaintext = plaintext;
            return this;
        }

        /**
         * Sets the stable error code for a failed lookup.
         *
         * @param errorCode error code
         * @return this builder
         */
        public Builder errorCode(String errorCode) {
            this.errorCode = errorCode;
            return this;
        }

        /**
         * Adds caller-provided audit attributes.
         *
         * <p>Entries are copied when {@link #build()} is called. A {@code null} map is ignored.</p>
         *
         * @param attributes custom attributes
         * @return this builder
         */
        public Builder attributes(Map<String, ?> attributes) {
            if (attributes != null) {
                this.attributes.putAll(attributes);
            }
            return this;
        }

        /**
         * Adds a single caller-provided audit attribute.
         *
         * <p>A {@code null} key is ignored; a {@code null} value is preserved.</p>
         *
         * @param key attribute key
         * @param value attribute value
         * @return this builder
         */
        public Builder attribute(String key, Object value) {
            if (key != null) {
                this.attributes.put(key, value);
            }
            return this;
        }

        /**
         * Builds an immutable audit event.
         *
         * @return audit event
         */
        public SensitivePlaintextAuditEvent build() {
            return new SensitivePlaintextAuditEvent(this);
        }
    }
}
