package io.github.jasper.mybatis.encrypt.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import io.github.jasper.mybatis.encrypt.core.metadata.FieldStorageMode;

/**
 * Declares encryption metadata for an entity property.
 *
 * <p>{@code column} always means the original business table column referenced by application SQL.
 * {@code storageColumn} means the real ciphertext storage column and defaults to {@code column}
 * when not configured.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface EncryptField {

    /**
     * Original business column name.
     *
     * <p>When omitted, the loader resolves it in this order:
     * {@code @TableField(value)}, JPA {@code @Column(name)}, then property-name snake_case.</p>
     */
    String column() default "";

    /**
     * Storage mode for ciphertext persistence.
     */
    FieldStorageMode storageMode() default FieldStorageMode.SAME_TABLE;

    /**
     * External table used when {@code storageMode=SEPARATE_TABLE}.
     */
    String storageTable() default "";

    /**
     * Real ciphertext storage column. Defaults to {@link #column()} when omitted.
     */
    String storageColumn() default "";

    /**
     * Entity property used as the business row identifier for separate-table storage.
     * When omitted, the loader infers it from {@link #sourceIdColumn()}.
     */
    String sourceIdProperty() default "";

    /**
     * Business-table identifier column. When omitted, the loader resolves the entity id column internally.
     */
    String sourceIdColumn() default "";

    /**
     * Separate-table identifier column. Defaults to {@code sourceIdColumn}.
     */
    String storageIdColumn() default "";

    /**
     * Cipher algorithm bean name.
     */
    String cipherAlgorithm() default "sm4";

    /**
     * Assisted equality query column.
     */
    String assistedQueryColumn() default "";

    /**
     * Assisted equality algorithm bean name.
     */
    String assistedQueryAlgorithm() default "sm3";

    /**
     * LIKE helper column.
     */
    String likeQueryColumn() default "";

    /**
     * LIKE helper algorithm bean name.
     */
    String likeQueryAlgorithm() default "normalizedLike";
}
