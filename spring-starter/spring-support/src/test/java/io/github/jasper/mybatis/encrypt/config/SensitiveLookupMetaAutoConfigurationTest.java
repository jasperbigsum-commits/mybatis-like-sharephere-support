package io.github.jasper.mybatis.encrypt.config;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.algorithm.AssistedQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.CipherAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.LikeQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.support.NormalizedLikeQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.support.PhoneNumberMaskLikeQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.support.Sm3AssistedQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.support.Sm4CipherAlgorithm;
import io.github.jasper.mybatis.encrypt.core.lookup.SensitivePlaintextAuditRecorder;
import io.github.jasper.mybatis.encrypt.core.lookup.SensitivePlaintextLookupService;
import io.github.jasper.mybatis.encrypt.core.metadata.AnnotationEncryptMetadataLoader;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@Tag("unit")
@Tag("config")
class SensitiveLookupMetaAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    SensitiveLookupMetaAutoConfiguration.class))
            .withBean(DataSource.class, SensitiveLookupMetaAutoConfigurationTest::dataSource)
            .withBean(DatabaseEncryptionProperties.class, DatabaseEncryptionProperties::new)
            .withBean(AnnotationEncryptMetadataLoader.class, AnnotationEncryptMetadataLoader::new)
            .withBean(EncryptMetadataRegistry.class,
                    () -> new EncryptMetadataRegistry(new DatabaseEncryptionProperties(), new AnnotationEncryptMetadataLoader()))
            .withBean(AlgorithmRegistry.class, SensitiveLookupMetaAutoConfigurationTest::algorithmRegistry);

    @Test
    void shouldRegisterLookupMetaBeans() {
        contextRunner.run(context -> {
            assertNotNull(context.getBean(SensitivePlaintextLookupService.class));
            assertNotNull(context.getBean(SensitivePlaintextAuditRecorder.class));
        });
    }

    private static DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:sensitive_lookup_meta_autoconfig;MODE=MYSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private static AlgorithmRegistry algorithmRegistry() {
        return new AlgorithmRegistry(
                Collections.<String, CipherAlgorithm>singletonMap("sm4", new Sm4CipherAlgorithm("unit-test-key")),
                Collections.<String, AssistedQueryAlgorithm>singletonMap("sm3", new Sm3AssistedQueryAlgorithm()),
                Collections.<String, LikeQueryAlgorithm>singletonMap("normalizedLike", new NormalizedLikeQueryAlgorithm())
        );
    }
}
