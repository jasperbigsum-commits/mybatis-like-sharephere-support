package io.github.jasper.mybatis.encrypt.logsafe.context.web;

import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the logsafe trace-context interceptor through standard MVC extension points.
 */
public class TraceContextWebMvcConfigurer implements WebMvcConfigurer {

    private final TraceContextInterceptor interceptor;

    /**
     * Creates a configurer that registers the supplied trace-context interceptor.
     *
     * @param interceptor trace-context interceptor
     */
    public TraceContextWebMvcConfigurer(TraceContextInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor);
    }
}
