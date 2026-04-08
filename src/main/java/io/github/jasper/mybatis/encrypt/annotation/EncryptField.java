package io.github.jasper.mybatis.encrypt.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import io.github.jasper.mybatis.encrypt.core.metadata.FieldStorageMode;

/**
 * 为实体属性声明加密元数据。
 *
 * <p>{@code column} 始终表示应用 SQL 引用的原始业务列，{@code storageColumn} 表示真实密文存储列；
 * 未配置时，{@code storageColumn} 默认与 {@code column} 保持一致。</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface EncryptField {

    /**
     * 原始业务列名。
     *
     * <p>省略时，加载器会按以下顺序解析：{@code @TableField(value)}、JPA {@code @Column(name)}，最后退回到属性名 snake_case。</p>
     *
     * @return 原始业务列名
     */
    String column() default "";

    /**
     * 密文持久化的存储模式。
     *
     * @return 字段存储模式
     */
    FieldStorageMode storageMode() default FieldStorageMode.SAME_TABLE;

    /**
     * 当 {@code storageMode=SEPARATE_TABLE} 时使用的外部表。
     *
     * @return 独立表表名
     */
    String storageTable() default "";

    /**
     * 实际密文存储列；省略时默认使用 {@link #column()}。
     *
     * @return 密文存储列名
     */
    String storageColumn() default "";

    /**
     * 独立表标识列；默认使用 {@code id}。
     *
     * @return 独立表 id 列名
     */
    String storageIdColumn() default "";

    /**
     * 加密算法 bean 名称。
     *
     * @return 加密算法 bean 名称
     */
    String cipherAlgorithm() default "sm4";

    /**
     * 辅助等值查询列。
     *
     * @return 辅助等值查询列名
     */
    String assistedQueryColumn() default "";

    /**
     * 辅助等值查询算法 bean 名称。
     *
     * @return 辅助查询算法 bean 名称
     */
    String assistedQueryAlgorithm() default "sm3";

    /**
     * LIKE 辅助列。
     *
     * @return LIKE 辅助列名
     */
    String likeQueryColumn() default "";

    /**
     * LIKE 辅助算法 bean 名称。
     *
     * @return LIKE 查询算法 bean 名称
     */
    String likeQueryAlgorithm() default "normalizedLike";
}
