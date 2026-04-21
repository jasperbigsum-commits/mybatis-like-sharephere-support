package io.github.jasper.mybatis.encrypt.config;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.core.mask.JdbcStoredSensitiveValueResolver;
import io.github.jasper.mybatis.encrypt.core.mask.SensitiveDataMasker;
import io.github.jasper.mybatis.encrypt.core.mask.SensitiveFieldMasker;
import io.github.jasper.mybatis.encrypt.core.mask.StoredSensitiveValueResolver;
import io.github.jasper.mybatis.encrypt.web.SensitiveResponseBodyAdvice;
import io.github.jasper.mybatis.encrypt.web.SensitiveResponseContextInterceptor;
import io.github.jasper.mybatis.encrypt.web.SensitiveResponseWebMvcConfigurer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import javax.sql.DataSource;
import java.util.Map;

/**
 * Spring MVC adapter for controller-boundary sensitive response masking.
 *
 * <p>The auto-configuration wires only boundary components: request scope management, response body
 * advice, and optional stored masked-value lookup when a {@link DataSource} is available. Core SQL
 * rewrite and decryption behavior remain part of the main MyBatis encryption configuration.</p>
 */
@AutoConfiguration(after = MybatisEncryptionAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({HandlerInterceptor.class, ResponseBodyAdvice.class, WebMvcConfigurer.class})
@ConditionalOnProperty(prefix = "mybatis.encrypt.sensitive-response", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class SensitiveResponseAutoConfiguration {

    /**
     * Creates the response masker used by {@link SensitiveResponseBodyAdvice}.
     *
     * <p>The bean combines three optional data sources: stored masked values loaded from the
     * database, registered LIKE algorithms used as fallback maskers, and custom
     * {@link SensitiveFieldMasker} beans referenced by {@code @SensitiveField(masker = ...)}.</p>
     *
     * @param storedSensitiveValueResolver resolver for stored masked values, may be {@code null}
     * @param algorithmRegistry registry used to resolve fallback LIKE algorithms
     * @param sensitiveFieldMaskers custom response-field maskers keyed by bean name, may be {@code null}
     * @return configured response masker
     */
    @Bean
    @ConditionalOnMissingBean
    public SensitiveDataMasker sensitiveDataMasker(
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            StoredSensitiveValueResolver storedSensitiveValueResolver,
            AlgorithmRegistry algorithmRegistry,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            Map<String, SensitiveFieldMasker> sensitiveFieldMaskers) {
        return new SensitiveDataMasker(storedSensitiveValueResolver, algorithmRegistry, sensitiveFieldMaskers);
    }

    /**
     * Creates the default JDBC-backed resolver that prefers masked values already stored in the
     * database.
     *
     * @param dataSources datasource beans keyed by bean name
     * @param algorithmRegistry registry used to derive assisted query values for lookup
     * @param properties plugin properties that define storage and masking metadata
     * @return JDBC-backed stored masked-value resolver
     */
    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(StoredSensitiveValueResolver.class)
    public StoredSensitiveValueResolver storedSensitiveValueResolver(Map<String, DataSource> dataSources,
                                                                    AlgorithmRegistry algorithmRegistry,
                                                                    DatabaseEncryptionProperties properties) {
        return new JdbcStoredSensitiveValueResolver(dataSources, algorithmRegistry, properties);
    }

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
