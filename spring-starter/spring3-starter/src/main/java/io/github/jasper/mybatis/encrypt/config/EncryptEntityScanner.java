package io.github.jasper.mybatis.encrypt.config;

import io.github.jasper.mybatis.encrypt.annotation.EncryptField;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.filter.RegexPatternTypeFilter;

import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 启动期实体扫描器。
 *
 * <p>当开启注解扫描时，自动扫描 Spring Boot 自动配置包下声明了 {@link EncryptField} 的实体，
 * 提前把规则注册到元数据中心，无需依赖首次 SQL 执行时的惰性加载。</p>
 */
public class EncryptEntityScanner implements SmartInitializingSingleton, ResourceLoaderAware {

    private final BeanFactory beanFactory;
    private final DatabaseEncryptionProperties properties;
    private final EncryptMetadataRegistry metadataRegistry;
    private ResourceLoader resourceLoader;

    /**
     * 创建启动期实体扫描器。
     *
     * @param beanFactory Spring BeanFactory
     * @param properties 插件配置属性
     * @param metadataRegistry 加密元数据注册中心
     */
    public EncryptEntityScanner(BeanFactory beanFactory,
                                DatabaseEncryptionProperties properties,
                                EncryptMetadataRegistry metadataRegistry) {
        this.beanFactory = beanFactory;
        this.properties = properties;
        this.metadataRegistry = metadataRegistry;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!properties.isScanEntityAnnotations()) {
            return;
        }
        for (String basePackage : resolveBasePackages()) {
            scanPackage(basePackage);
        }
    }

    private void scanPackage(String basePackage) {
        ClassPathScanningCandidateComponentProvider provider = new ClassPathScanningCandidateComponentProvider(false);
        provider.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(".*")));
        if (resourceLoader != null) {
            provider.setResourceLoader(resourceLoader);
        }
        provider.findCandidateComponents(basePackage).forEach(definition -> {
            try {
                Class<?> type = Class.forName(definition.getBeanClassName());
                if (hasEncryptField(type)) {
                    metadataRegistry.registerEntityType(type);
                }
            } catch (ClassNotFoundException ignore) {
            }
        });
    }

    private Set<String> resolveBasePackages() {
        Set<String> packages = new LinkedHashSet<>(properties.getScanPackages());
        if (packages.isEmpty() && AutoConfigurationPackages.has(beanFactory)) {
            packages.addAll(AutoConfigurationPackages.get(beanFactory));
        }
        return packages;
    }

    private boolean hasEncryptField(Class<?> type) {
        for (Field field : type.getDeclaredFields()) {
            if (field.getAnnotation(EncryptField.class) != null) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void setResourceLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }
}
