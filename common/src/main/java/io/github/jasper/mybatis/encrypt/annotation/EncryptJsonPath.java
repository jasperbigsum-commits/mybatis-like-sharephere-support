package io.github.jasper.mybatis.encrypt.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * 描述一个精确 JSON path 的独立表绑定关系。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
public @interface EncryptJsonPath {

    /**
     * 精确 JSON path。
     *
     * @return 精确 JSON path
     */
    String path();

    /**
     * 密文独立表名。
     *
     * @return 独立表名
     */
    String storageTable() default "";

    /**
     * 独立表主键列名。
     *
     * @return 独立表主键列名
     */
    String storageIdColumn() default "id";

    /**
     * 主表 JSON path 对应的 hash 映射列。
     *
     * @return hash 列名
     */
    String hashColumn() default "";

    /**
     * 独立表密文列。
     *
     * @return 密文列名
     */
    String cipherColumn() default "";

    /**
     * path 级覆盖密文算法 bean 名称。
     *
     * @return 密文算法 bean 名称
     */
    String cipherAlgorithm() default "";

    /**
     * path 级覆盖 hash 算法 bean 名称。
     *
     * @return assisted query 算法 bean 名称
     */
    String assistedQueryAlgorithm() default "";
}
