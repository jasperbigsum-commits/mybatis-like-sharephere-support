package io.github.jasper.mybatis.encrypt.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 为 JSON 字符串属性声明精确 path 加密元数据。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface EncryptJsonField {

    /**
     * 当前字段来源的物理表名。
     *
     * @return 字段来源表名
     */
    String table() default "";

    /**
     * 主表 JSON 物理列名。
     *
     * @return JSON 列名
     */
    String column() default "";

    /**
     * 字段级默认密文算法 bean 名称。
     *
     * @return 密文算法 bean 名称
     */
    String cipherAlgorithm() default "sm4";

    /**
     * 字段级默认 hash 算法 bean 名称。
     *
     * @return assisted query 算法 bean 名称
     */
    String assistedQueryAlgorithm() default "sm3";

    /**
     * 需要加密的精确 JSON path 集合。
     *
     * @return JSON path 规则
     */
    EncryptJsonPath[] paths();
}
