package io.github.jasper.mybatis.encrypt.core.mask;

import io.github.jasper.mybatis.encrypt.annotation.SensitiveField;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable context passed to a {@link SensitiveFieldMasker}.
 *
 * <p>The context exposes only response-boundary information: the object currently being masked,
 * the field name, the declaring type, the original {@link SensitiveField} annotation and parsed
 * {@code key=value} options. It deliberately does not expose storage metadata or SQL state, keeping
 * custom maskers independent from persistence concerns.</p>
 */
public final class SensitiveFieldMaskingContext {

    private final Object owner;
    private final Class<?> declaringClass;
    private final String fieldName;
    private final SensitiveField annotation;
    private final Map<String, String> options;

    /**
     * Creates a masking context.
     *
     * @param owner object that owns the masked field
     * @param declaringClass class that declares the field
     * @param fieldName field name
     * @param annotation field annotation
     * @param options parsed annotation options
     */
    public SensitiveFieldMaskingContext(Object owner,
                                        Class<?> declaringClass,
                                        String fieldName,
                                        SensitiveField annotation,
                                        Map<String, String> options) {
        this.owner = owner;
        this.declaringClass = declaringClass;
        this.fieldName = fieldName;
        this.annotation = annotation;
        this.options = options == null
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(options));
    }

    /**
     * Returns the response object that owns the field.
     *
     * @return owner object
     */
    public Object owner() {
        return owner;
    }

    /**
     * Returns the class that declares the masked field.
     *
     * @return declaring class
     */
    public Class<?> declaringClass() {
        return declaringClass;
    }

    /**
     * Returns the masked field name.
     *
     * @return field name
     */
    public String fieldName() {
        return fieldName;
    }

    /**
     * Returns the source annotation.
     *
     * @return annotation
     */
    public SensitiveField annotation() {
        return annotation;
    }

    /**
     * Returns all parsed options.
     *
     * @return immutable options map
     */
    public Map<String, String> options() {
        return options;
    }

    /**
     * Returns one parsed option.
     *
     * @param name option name
     * @return option value, or {@code null}
     */
    public String option(String name) {
        return options.get(name);
    }
}
