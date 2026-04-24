package io.github.jasper.mybatis.encrypt.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Triggers masking for one controller method while reusing the surrounding controller's
 * {@link SensitiveResponse} strategy.
 *
 * <p>This annotation is intended for methods that want to consume an already-open masking context
 * without owning the right to open one. When used on a Spring bean method such as a service or
 * assembler method, the method-level aspect checks the current
 * {@link io.github.jasper.mybatis.encrypt.core.mask.SensitiveDataContext} after the method returns
 * and masks the returned object only if the controller layer has already opened a masking scope.
 * Without an active context, the method remains a pass-through.</p>
 *
 * <p>This annotation therefore does not replace {@link SensitiveResponse}. Controller entrypoints
 * must still use {@link SensitiveResponse} to declare whether the current request opens a masking
 * boundary. True same-class self-invocation still depends on the caller's proxy model; if a method
 * is invoked through {@code this.xxx()}, standard Spring proxy interception does not apply.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SensitiveResponseTrigger {
}
