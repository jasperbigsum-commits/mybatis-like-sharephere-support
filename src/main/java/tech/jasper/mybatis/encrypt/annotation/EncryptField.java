package tech.jasper.mybatis.encrypt.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import tech.jasper.mybatis.encrypt.core.metadata.FieldStorageMode;

/**
 * 声明实体字段的加密规则。
 *
 * <p>该注解用于把业务属性映射到数据库中的主加密列、辅助等值查询列和 LIKE 查询列。
 * 当字段上未显式指定列名时，框架会基于属性名按下划线风格推导默认列名。</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface EncryptField {

    /**
     * 主加密列名，默认根据属性名推导。
     */
    String column() default "";

    /**
     * 字段存储模式，默认与业务表存储在同一张表。
     */
    FieldStorageMode storageMode() default FieldStorageMode.SAME_TABLE;

    /**
     * 独立加密表名，仅在 storageMode=SEPARATE_TABLE 时生效。
     */
    String storageTable() default "";

    /**
     * 独立加密表中的密文列名，默认与 column 相同。
     */
    String storageColumn() default "";

    /**
     * 业务实体中的主键属性名，用于和独立加密表做关联。
     */
    String sourceIdProperty() default "id";

    /**
     * 业务主表中的主键列名，默认根据 sourceIdProperty 推导。
     */
    String sourceIdColumn() default "";

    /**
     * 独立加密表中的关联列名，默认与 sourceIdColumn 相同。
     */
    String storageIdColumn() default "";

    /**
     * 主加密算法 Bean 名称，默认使用 sm4。
     */
    String cipherAlgorithm() default "sm4";

    /**
     * 辅助等值查询列名。
     */
    String assistedQueryColumn() default "";

    /**
     * 辅助等值查询算法 Bean 名称，默认使用 sm3。
     */
    String assistedQueryAlgorithm() default "sm3";

    /**
     * LIKE 查询辅助列名。
     */
    String likeQueryColumn() default "";

    /**
     * LIKE 查询算法 Bean 名称。
     */
    String likeQueryAlgorithm() default "normalizedLike";
}
