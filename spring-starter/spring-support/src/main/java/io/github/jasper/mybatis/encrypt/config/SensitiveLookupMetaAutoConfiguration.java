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

    @Bean
    @ConditionalOnMissingBean
    public SensitivePlaintextAuditRecorder sensitivePlaintextAuditRecorder() {
        return SensitivePlaintextAuditRecorder.noOp();
    }

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
