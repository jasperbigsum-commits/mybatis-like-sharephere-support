package io.github.jasper.mybatis.encrypt.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记 Mapper 方法跳过 SQL 加密重写与结果解密处理。
 *
 * <p>当 Mapper 方法标注此注解后，对应的 SQL 将直接透传给数据库，不经过
 * {@code SqlRewriteEngine} 解析与改写，查询结果也不会触发解密回填。
 * 适用于操作的表没有加密字段、SQL 包含 JSqlParser 无法解析的方言语法、
 * 或查询视图/派生表等无需加密处理的场景。</p>
 *
 * <p>该注解不会影响标注方法之外的其他 Mapper 方法，也不改变全局配置。</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SkipSqlRewrite {
}
