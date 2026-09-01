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
 *
 * <p>它主要面向两类场景：一是 Mapper 返回的 DTO 本身没有 {@code @EncryptField} 注解；二是
 * SQL 使用了别名、派生表或 join 投影，导致仅凭 DTO 属性名无法直接回溯到来源字段。该注解
 * 仍然遵守当前结果映射边界，不会替代 {@code resultMap} 做对象装配，也不会对复杂表达式列
 * 做额外语义推断。</p>
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
