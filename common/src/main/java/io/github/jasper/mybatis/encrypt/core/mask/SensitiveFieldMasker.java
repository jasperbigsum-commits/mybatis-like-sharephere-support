package io.github.jasper.mybatis.encrypt.core.mask;

/**
 * Custom response-field masking SPI used by {@link io.github.jasper.mybatis.encrypt.annotation.SensitiveField}.
 *
 * <p>Implementations are intended for presentation-layer masking only. They receive the current
 * clear-text value and immutable annotation configuration, and must return the value that should be
 * written back to the response DTO. Implementations should be deterministic and should not perform
 * database writes or mutate the supplied owner object.</p>
 */
public interface SensitiveFieldMasker {

    /**
     * Masks one response field value.
     *
     * @param value current field value, never blank
     * @param context field and annotation context
     * @return replacement value; returning {@code null} keeps the current value unchanged
     */
    String mask(String value, SensitiveFieldMaskingContext context);
}
