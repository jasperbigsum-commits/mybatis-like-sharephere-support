package io.github.jasper.mybatis.encrypt.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enables request-side sensitive field hydration for one controller endpoint or controller class.
 *
 * <p>When this annotation is present, the Spring MVC adapter may rewrite supported request payloads
 * before controller argument binding. The rewrite restores unchanged masked fields from
 * {@code sensitiveSubmitMeta} or compatible sensitive-input objects back into the original plaintext
 * DTO fields. This annotation deliberately scopes the behavior to selected endpoints so unrelated
 * requests are not parsed or rewritten.</p>
 *
 * <p>The hydration is an internal conversion. It does not return plaintext to the caller and the
 * built-in lookup service does not emit plaintext-view audit events for this path.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface SensitiveRequestHydration {
}
