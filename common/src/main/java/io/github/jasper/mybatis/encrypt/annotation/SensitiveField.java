package io.github.jasper.mybatis.encrypt.annotation;

import io.github.jasper.mybatis.encrypt.core.mask.SensitiveFieldMasker;
import io.github.jasper.mybatis.encrypt.core.mask.SensitiveFieldMaskingContext;
import io.github.jasper.mybatis.encrypt.core.mask.SensitiveMaskType;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a response DTO {@link String} field as maskable at the controller boundary.
 *
 * <p>This annotation is a fallback or explicit object-graph masking mechanism. It is useful for
 * manually assembled response DTOs that were not directly recorded by MyBatis result decryption.
 * Fields annotated with {@code @SensitiveField} must be non-static, non-final {@code String}
 * fields; {@link io.github.jasper.mybatis.encrypt.core.mask.SensitiveDataMasker} validates that
 * constraint when it builds class bindings.</p>
 *
 * <p>If a field has already been replaced by a database stored masked value, it should normally not
 * be annotated again, because storage-layer masking is treated as final output.</p>
 *
 * <p>For custom output models, {@link #masker()} can reference a
 * {@link io.github.jasper.mybatis.encrypt.core.mask.SensitiveFieldMasker} bean and {@link #options()}
 * can pass simple {@code key=value} configuration to it. If the desired masking rule is already
 * registered as a LIKE algorithm, {@link #likeAlgorithm()} can reference that algorithm directly.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface SensitiveField {

    /**
     * Mask style.
     *
     * <p>{@link SensitiveMaskType#AUTO} infers a style from the field name and falls back to
     * {@link SensitiveMaskType#DEFAULT}.</p>
     *
     * @return mask style
     */
    SensitiveMaskType type() default SensitiveMaskType.AUTO;

    /**
     * Overrides the number of leading characters to keep.
     *
     * <p>A negative value means the selected {@link #type()} default should be used.</p>
     *
     * @return leading characters to keep
     */
    int keepFirst() default -1;

    /**
     * Overrides the number of trailing characters to keep.
     *
     * <p>A negative value means the selected {@link #type()} default should be used.</p>
     *
     * @return trailing characters to keep
     */
    int keepLast() default -1;

    /**
     * Replacement character used for masked positions.
     *
     * @return replacement character
     */
    char maskChar() default '*';

    /**
     * Custom response-field masker bean name.
     * @see SensitiveFieldMasker#mask(String, SensitiveFieldMaskingContext)
     * <p>When set, this custom masker has the highest priority and receives {@link #options()} as
     * parsed {@code key=value} pairs.</p>
     *
     * @return custom masker bean name
     */
    String masker() default "";

    /**
     * LIKE algorithm bean name reused as a response masking algorithm.
     *
     * <p>This is useful when response masking should be byte-for-byte consistent with
     * {@code maskedAlgorithm} or an existing LIKE-derived masking algorithm.</p>
     *
     * @return LIKE algorithm bean name
     */
    String likeAlgorithm() default "";

    /**
     * Custom options passed to {@link #masker()}.
     *
     * <p>Each entry must use {@code key=value}. Duplicate keys and blank keys are rejected when
     * the field binding is built.</p>
     *
     * @return custom options
     */
    String[] options() default {};
}
