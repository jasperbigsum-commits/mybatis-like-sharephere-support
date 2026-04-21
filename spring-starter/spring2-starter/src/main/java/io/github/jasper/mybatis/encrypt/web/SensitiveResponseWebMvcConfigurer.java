package io.github.jasper.mybatis.encrypt.web;

import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the sensitive response interceptor without replacing user MVC configuration.
 *
 * <p>The configurer keeps the web adapter opt-in and additive: applications can still define their
 * own {@link WebMvcConfigurer} beans, interceptor ordering rules, and message converters.</p>
 */
public class SensitiveResponseWebMvcConfigurer implements WebMvcConfigurer {

    private final SensitiveResponseContextInterceptor interceptor;

    /**
     * Creates an MVC configurer that registers the sensitive-response interceptor.
     *
     * @param interceptor interceptor responsible for opening and closing response scopes
     */
    public SensitiveResponseWebMvcConfigurer(SensitiveResponseContextInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor);
    }
}
