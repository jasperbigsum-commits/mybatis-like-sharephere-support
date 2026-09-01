package io.github.jasper.mybatis.encrypt.config;

import org.apache.ibatis.plugin.Interceptor;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Resolves the built-in JEECG write interceptor from the current Spring container.
 *
 * <p>Only JEECG's {@code MybatisInterceptor} is adapted automatically here.
 * Project-specific write-time mutation logic should be exposed through
 * {@link io.github.jasper.mybatis.encrypt.plugin.WriteParameterPreprocessor}
 * instead of being inferred from arbitrary MyBatis interceptors.</p>
 */
final class JeecgInterceptorResolver {

    static final String MYBATIS_INTERCEPTOR_CLASS = "org.jeecg.config.mybatis.MybatisInterceptor";

    private final BeanFactory beanFactory;

    JeecgInterceptorResolver(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    List<Interceptor> resolve() {
        if (!(beanFactory instanceof org.springframework.beans.factory.ListableBeanFactory)) {
            return Collections.emptyList();
        }
        org.springframework.beans.factory.ListableBeanFactory listableBeanFactory =
                (org.springframework.beans.factory.ListableBeanFactory) beanFactory;
        List<Interceptor> matches = new ArrayList<>();
        collectInterceptor(listableBeanFactory, MYBATIS_INTERCEPTOR_CLASS, matches);
        AnnotationAwareOrderComparator.sort(matches);
        return matches;
    }

    private void collectInterceptor(org.springframework.beans.factory.ListableBeanFactory beanFactory,
                                    String targetClassName,
                                    Collection<Interceptor> matches) {
        String[] beanNames = beanFactory.getBeanNamesForType(Interceptor.class, false, false);
        for (String beanName : beanNames) {
            // Spring Framework 5.1.x used by Boot 2.1 only exposes getType(String).
            Class<?> beanType = beanFactory.getType(beanName);
            if (!matchesTypeName(beanType, targetClassName)) {
                continue;
            }
            Interceptor interceptor = (Interceptor) beanFactory.getBean(beanName);
            Class<?> targetType = AopUtils.getTargetClass(interceptor);
            Class<?> effectiveType = targetType != null ? targetType : beanType;
            if (!matchesTypeName(effectiveType, targetClassName)) {
                continue;
            }
            matches.add(interceptor);
        }
    }

    private boolean matchesTypeName(Class<?> type, String targetClassName) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            if (targetClassName.equals(current.getName())) {
                return true;
            }
            current = current.getSuperclass();
        }
        return false;
    }
}
