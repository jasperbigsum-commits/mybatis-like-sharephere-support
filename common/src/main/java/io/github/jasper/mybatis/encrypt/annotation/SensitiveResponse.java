package io.github.jasper.mybatis.encrypt.annotation;

import io.github.jasper.mybatis.encrypt.core.mask.SensitiveResponseStrategy;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enables controller-boundary sensitive response handling for one endpoint or controller class.
 *
 * <p>When this annotation is present, the Spring MVC adapter opens a
 * {@link io.github.jasper.mybatis.encrypt.core.mask.SensitiveDataContext} before the controller
 * method runs. If {@link #returnSensitive()} is {@code false}, MyBatis result decryption records
 * decrypted field references in that context and the response advice masks those references before
 * HTTP message conversion.</p>
 *
 * <p>Method-level annotations override class-level annotations through Spring's merged annotation
 * lookup. The annotation does not change SQL execution, encryption or decryption by itself; it only
 * controls the final response boundary decision. If internal service or assembler methods want to
 * consume the already-open request scope, they may use {@link SensitiveResponseTrigger}, which
 * never opens a new scope by itself.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface SensitiveResponse {

    /**
     * Returns whether decrypted sensitive values may be returned as-is.
     *
     * <p>When {@code true}, the masking scope is still opened but decryption records are not
     * collected and the response body advice becomes a pass-through. When {@code false}, the
     * selected {@link #strategy()} controls how the response is parsed and replaced.</p>
     *
     * @return whether sensitive values may be returned as-is
     */
    boolean returnSensitive() default false;

    /**
     * Response parsing strategy used when {@link #returnSensitive()} is {@code false}.
     *
     * @return response masking strategy
     */
    SensitiveResponseStrategy strategy() default SensitiveResponseStrategy.RECORDED_ONLY;
}
