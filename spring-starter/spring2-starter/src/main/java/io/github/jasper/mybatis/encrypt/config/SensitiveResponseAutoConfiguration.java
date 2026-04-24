package io.github.jasper.mybatis.encrypt.config;

import io.github.jasper.mybatis.encrypt.core.mask.SensitiveDataMasker;
import io.github.jasper.mybatis.encrypt.web.SensitiveResponseBodyAdvice;
import io.github.jasper.mybatis.encrypt.web.SensitiveResponseContextInterceptor;
import io.github.jasper.mybatis.encrypt.web.SensitiveResponseWebMvcConfigurer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Spring MVC adapter for controller-boundary sensitive response masking.
 *
 * <p>The auto-configuration wires only boundary components: request scope management and response
 * body advice. Core masking beans are provided by {@link SensitiveMaskingAutoConfiguration}, while
 * SQL rewrite and decryption behavior remain part of the main MyBatis encryption configuration.</p>
 */
@AutoConfiguration(after = MybatisEncryptionAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({HandlerInterceptor.class, ResponseBodyAdvice.class, WebMvcConfigurer.class})
@ConditionalOnProperty(prefix = "mybatis.encrypt.sensitive-response", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class SensitiveResponseAutoConfiguration {

    /**
     * Creates the MVC interceptor that opens and closes controller response scopes.
     *
     * @return controller-boundary sensitive-response scope interceptor
     */
    @Bean
    @ConditionalOnMissingBean
    public SensitiveResponseContextInterceptor sensitiveResponseContextInterceptor() {
        return new SensitiveResponseContextInterceptor();
    }

    /**
     * Creates the response advice that performs the final masking pass before serialization.
     *
     * @param sensitiveDataMasker response masker used for in-place DTO replacement
     * @return response body advice for controller-boundary masking
     */
    @Bean
    @ConditionalOnMissingBean
    public SensitiveResponseBodyAdvice sensitiveResponseBodyAdvice(SensitiveDataMasker sensitiveDataMasker) {
        return new SensitiveResponseBodyAdvice(sensitiveDataMasker);
    }

    /**
     * Registers the interceptor through standard MVC extension points.
     *
     * @param sensitiveResponseContextInterceptor interceptor that manages response scopes
     * @return MVC configurer that registers the interceptor
     */
    @Bean
    @ConditionalOnMissingBean
    public SensitiveResponseWebMvcConfigurer sensitiveResponseWebMvcConfigurer(
            SensitiveResponseContextInterceptor sensitiveResponseContextInterceptor) {
        return new SensitiveResponseWebMvcConfigurer(sensitiveResponseContextInterceptor);
    }
}
