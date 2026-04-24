package io.github.jasper.mybatis.encrypt.support;

import io.github.jasper.mybatis.encrypt.annotation.SensitiveResponseTrigger;
import io.github.jasper.mybatis.encrypt.core.mask.SensitiveDataMasker;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;

/**
 * Applies one masking pass to non-controller methods annotated with {@link SensitiveResponseTrigger}
 * only when the current thread already has an active masking context.
 */
@Aspect
public class SensitiveResponseTriggerAspect {

    private final SensitiveDataMasker sensitiveDataMasker;

    public SensitiveResponseTriggerAspect(SensitiveDataMasker sensitiveDataMasker) {
        this.sensitiveDataMasker = sensitiveDataMasker;
    }

    @Around("@annotation(io.github.jasper.mybatis.encrypt.annotation.SensitiveResponseTrigger)")
    public Object aroundTriggeredMethod(ProceedingJoinPoint joinPoint) throws Throwable {
        Method method = mostSpecificMethod(joinPoint);
        Class<?> targetClass = joinPoint.getTarget() == null ? method.getDeclaringClass() : joinPoint.getTarget().getClass();
        if (isControllerType(targetClass)) {
            return joinPoint.proceed();
        }
        Object result = joinPoint.proceed();
        return sensitiveDataMasker.mask(result);
    }

    private Method mostSpecificMethod(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Class<?> targetClass = joinPoint.getTarget() == null ? method.getDeclaringClass() : joinPoint.getTarget().getClass();
        return AopUtils.getMostSpecificMethod(method, targetClass);
    }

    private boolean isControllerType(Class<?> targetClass) {
        return AnnotatedElementUtils.hasAnnotation(targetClass, Controller.class)
                || AnnotatedElementUtils.hasAnnotation(targetClass, RestController.class);
    }
}
