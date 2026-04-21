package io.github.jasper.mybatis.encrypt.core.mask;

/**
 * Built-in response masking styles used by {@link io.github.jasper.mybatis.encrypt.annotation.SensitiveField}.
 *
 * <p>These styles are intentionally lightweight and deterministic. They are applied only at the
 * controller response boundary as a presentation concern, and they do not affect stored values,
 * SQL rewriting, encryption, or decryption behavior.</p>
 */
public enum SensitiveMaskType {

    /**
     * Infers a concrete style from the field name and falls back to {@link #DEFAULT}.
     */
    AUTO,

    /**
     * Keeps only the trailing four characters, masking the rest.
     */
    DEFAULT,

    /**
     * Phone-like value that keeps only the trailing four characters.
     */
    PHONE,

    /**
     * Identity-card-like value that keeps the first three and last three characters.
     */
    ID_CARD,

    /**
     * Bank-card-like value that keeps the first six and last four characters.
     */
    BANK_CARD,

    /**
     * Name-like value that keeps only the first character.
     */
    NAME,

    /**
     * Email-like value that masks the local part and keeps the domain unchanged.
     */
    EMAIL,

    /**
     * Masks the full value without keeping any original character.
     */
    ALL
}
