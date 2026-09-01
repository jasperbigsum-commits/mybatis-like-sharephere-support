package io.github.jasper.mybatis.encrypt.config;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.core.lookup.DefaultSensitivePlaintextLookupService;
import io.github.jasper.mybatis.encrypt.core.lookup.SensitivePlaintextAuditRecorder;
import io.github.jasper.mybatis.encrypt.core.lookup.SensitivePlaintextLookupService;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.Map;

/**
 * Auto-configuration for sensitive lookup meta services.
 */
@Configuration(proxyBeanMethods = false)
@AutoConfigureAfter(name = "io.github.jasper.mybatis.encrypt.config.MybatisEncryptionAutoConfiguration")
@ConditionalOnClass(SensitivePlaintextLookupService.class)
@ConditionalOnProperty(prefix = "mybatis.encrypt", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SensitiveLookupMetaAutoConfiguration {

    /**
     * Registers the default no-op plaintext lookup audit recorder.
     *
     * @return no-op audit recorder used when the application does not provide one
     */
    @Bean
    @ConditionalOnMissingBean
    public SensitivePlaintextAuditRecorder sensitivePlaintextAuditRecorder() {
        return SensitivePlaintextAuditRecorder.noOp();
    }

    /**
     * Registers the default sensitive plaintext lookup service.
     *
     * <p>The service is used by explicit business plaintext lookup and by internal request
     * hydration. Internal request hydration calls {@code lookupInternal(...)} and does not emit
     * plaintext-view audit events.</p>
     *
     * @param dataSources available datasource beans
     * @param encryptMetadataRegistry encryption metadata registry
     * @param algorithmRegistry algorithm registry for decrypting resolved ciphertext
     * @param properties encryption properties, including SQL dialect quoting
     * @param auditRecorder audit recorder for explicit plaintext lookup
     * @return default plaintext lookup service
     */
    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(SensitivePlaintextLookupService.class)
    public SensitivePlaintextLookupService sensitivePlaintextLookupService(
            Map<String, DataSource> dataSources,
            EncryptMetadataRegistry encryptMetadataRegistry,
            AlgorithmRegistry algorithmRegistry,
            DatabaseEncryptionProperties properties,
            SensitivePlaintextAuditRecorder auditRecorder) {
        return new DefaultSensitivePlaintextLookupService(
                dataSources,
                encryptMetadataRegistry,
                algorithmRegistry,
                properties,
                auditRecorder
        );
    }
}
