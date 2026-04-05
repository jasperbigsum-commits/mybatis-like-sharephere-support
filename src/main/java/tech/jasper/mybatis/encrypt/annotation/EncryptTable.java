package tech.jasper.mybatis.encrypt.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
/**
 * 声明实体对应的数据库表名。
 *
 * <p>当未声明该注解时，框架会尝试读取 MyBatis-Plus 的表注解，仍然拿不到时再按
 * 类名转下划线风格推导默认表名。</p>
 */
public @interface EncryptTable {

    /**
     * 数据库表名。
     */
    String value() default "";
}
