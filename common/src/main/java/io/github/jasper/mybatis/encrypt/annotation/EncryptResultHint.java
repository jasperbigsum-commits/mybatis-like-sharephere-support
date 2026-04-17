package io.github.jasper.mybatis.encrypt.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 为返回 DTO 无加密注解的查询方法提供结果推断提示。
 *
 * <p>该注解不会重新定义字段规则，只负责在构建结果解密计划前预热来源实体或来源表的加密元数据，
 * 让现有的 SQL 投影列、ResultMap 列映射和自动驼峰映射可以继续完成属性推断。</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface EncryptResultHint {

    /**
     * 需要预热的来源实体类型。
     *
     * @return 来源实体类型
     */
    Class<?>[] entities() default {};

    /**
     * 需要预热的来源物理表名。
     *
     * @return 来源物理表名
     */
    String[] tables() default {};
}
