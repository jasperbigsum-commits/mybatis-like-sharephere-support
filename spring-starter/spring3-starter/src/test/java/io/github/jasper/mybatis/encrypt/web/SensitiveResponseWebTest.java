package io.github.jasper.mybatis.encrypt.web;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.algorithm.support.PhoneNumberMaskLikeQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.annotation.SensitiveField;
import io.github.jasper.mybatis.encrypt.annotation.SensitiveResponse;
import io.github.jasper.mybatis.encrypt.annotation.SensitiveResponseTrigger;
import io.github.jasper.mybatis.encrypt.core.mask.SensitiveDataContext;
import io.github.jasper.mybatis.encrypt.core.mask.SensitiveDataMasker;
import io.github.jasper.mybatis.encrypt.core.mask.SensitiveResponseStrategy;
import io.github.jasper.mybatis.encrypt.core.mask.StoredSensitiveValueResolver;
import io.github.jasper.mybatis.encrypt.support.SensitiveResponseTriggerAspect;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.util.Collections;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
@Tag("web")
class SensitiveResponseWebTest {

    /**
     * 测试目的：验证敏感响应脱敏能按记录值、存储态脱敏列或自定义脱敏器选择正确来源。
     * 测试场景：构造控制器作用域、敏感字段注解和数据库脱敏值，断言响应写出前的脱敏结果和作用域释放顺序。
     */
    @Test
    void shouldOpenControllerScopeAndMaskRecordedReferenceBeforeResponseWrite() throws Exception {
        SensitiveResponseContextInterceptor interceptor = new SensitiveResponseContextInterceptor();
        SensitiveResponseBodyAdvice advice = new SensitiveResponseBodyAdvice(new SensitiveDataMasker());
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        DemoController controller = new DemoController();
        HandlerMethod handlerMethod = handlerMethod(controller, "masked");
        DemoDto dto = new DemoDto("13800138000");

        assertTrue(interceptor.preHandle(request, response, handlerMethod));
        SensitiveDataContext.record(dto, "phone", "13800138000", null);
        assertTrue(advice.supports(null, null));
        advice.beforeBodyWrite(dto, null, null, null, null, null);
        interceptor.afterCompletion(request, response, handlerMethod, null);

        assertEquals("*******8000", dto.phone);
        assertFalse(SensitiveDataContext.isActive());
    }

    /**
     * 测试目的：验证敏感响应脱敏能按记录值、存储态脱敏列或自定义脱敏器选择正确来源。
     * 测试场景：构造控制器作用域、敏感字段注解和数据库脱敏值，断言响应写出前的脱敏结果和作用域释放顺序。
     */
    @Test
    void shouldKeepSensitiveValueWhenControllerAllowsSensitiveResponse() throws Exception {
        SensitiveResponseContextInterceptor interceptor = new SensitiveResponseContextInterceptor();
        SensitiveResponseBodyAdvice advice = new SensitiveResponseBodyAdvice(new SensitiveDataMasker());
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        DemoController controller = new DemoController();
        HandlerMethod handlerMethod = handlerMethod(controller, "plain");
        DemoDto dto = new DemoDto("13800138000");

        assertTrue(interceptor.preHandle(request, response, handlerMethod));
        SensitiveDataContext.record(dto, "phone", "13800138000", null);
        assertFalse(advice.supports(null, null));
        advice.beforeBodyWrite(dto, null, null, null, null, null);
        interceptor.afterCompletion(request, response, handlerMethod, null);

        assertEquals("13800138000", dto.phone);
        assertFalse(SensitiveDataContext.isActive());
    }

    /**
     * 测试目的：验证敏感响应脱敏能按记录值、存储态脱敏列或自定义脱敏器选择正确来源。
     * 测试场景：构造控制器作用域、敏感字段注解和数据库脱敏值，断言响应写出前的脱敏结果和作用域释放顺序。
     */
    @Test
    void shouldPreferStoredMaskedValueWithoutSensitiveFieldAnnotation() throws Exception {
        SensitiveResponseContextInterceptor interceptor = new SensitiveResponseContextInterceptor();
        StoredSensitiveValueResolver resolver = records -> Map.of(records.iterator().next(), "138****8000");
        SensitiveResponseBodyAdvice advice = new SensitiveResponseBodyAdvice(new SensitiveDataMasker(resolver, null));
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        DemoController controller = new DemoController();
        HandlerMethod handlerMethod = handlerMethod(controller, "maskedStored");
        StoredMaskDto dto = new StoredMaskDto("13800138000");

        assertTrue(interceptor.preHandle(request, response, handlerMethod));
        SensitiveDataContext.record(dto, "phone", "13800138000", null);
        assertTrue(advice.supports(null, null));
        advice.beforeBodyWrite(dto, null, null, null, null, null);
        interceptor.afterCompletion(request, response, handlerMethod, null);

        assertEquals("138****8000", dto.phone);
        assertFalse(SensitiveDataContext.isActive());
    }

    /**
     * 测试目的：验证敏感响应脱敏能按记录值、存储态脱敏列或自定义脱敏器选择正确来源。
     * 测试场景：构造控制器作用域、敏感字段注解和数据库脱敏值，断言响应写出前的脱敏结果和作用域释放顺序。
     */
    @Test
    void shouldCloseNestedAnnotatedScopesInLifoOrderWithinSameRequest() throws Exception {
        SensitiveResponseContextInterceptor interceptor = new SensitiveResponseContextInterceptor();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        DemoController controller = new DemoController();
        HandlerMethod outer = handlerMethod(controller, "masked");
        HandlerMethod inner = handlerMethod(controller, "maskedStored");

        assertTrue(interceptor.preHandle(request, response, outer));
        assertTrue(SensitiveDataContext.isActive());
        assertTrue(interceptor.preHandle(request, response, inner));
        assertTrue(SensitiveDataContext.isActive());

        interceptor.afterCompletion(request, response, inner, null);
        assertTrue(SensitiveDataContext.isActive());

        interceptor.afterCompletion(request, response, outer, null);
        assertFalse(SensitiveDataContext.isActive());
    }

    /**
     * 测试目的：验证方法级触发注解不会覆盖 controller 已声明的明文返回决策。
     * 测试场景：类上声明 returnSensitive=true，方法上只标触发注解；断言上下文仍按 controller 配置透传，不会进入响应脱敏 advice。
     */
    @Test
    void shouldNotOverrideControllerReturnSensitiveWhenTriggerIsPresent() throws Exception {
        SensitiveResponseContextInterceptor interceptor = new SensitiveResponseContextInterceptor();
        SensitiveResponseBodyAdvice advice = new SensitiveResponseBodyAdvice(new SensitiveDataMasker());
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        TriggeredController controller = new TriggeredController();
        HandlerMethod handlerMethod = handlerMethod(TriggeredController.class, controller, "triggeredMasked");
        DemoDto dto = new DemoDto("13800138000");

        assertTrue(interceptor.preHandle(request, response, handlerMethod));
        SensitiveDataContext.record(dto, "phone", "13800138000", null);
        assertFalse(advice.supports(null, null));
        advice.beforeBodyWrite(dto, null, null, null, null, null);
        interceptor.afterCompletion(request, response, handlerMethod, null);

        assertEquals("13800138000", dto.phone);
        assertFalse(SensitiveDataContext.isActive());
    }

    /**
     * 测试目的：验证方法级触发注解在没有 controller 开启上下文时不做任何动作。
     * 测试场景：控制器类本身未声明 SensitiveResponse，只在方法上标触发注解；断言不会打开上下文，也不会进入响应脱敏 advice。
     */
    @Test
    void shouldIgnoreStandaloneTriggerWhenControllerLacksSensitiveResponseBoundary() throws Exception {
        SensitiveResponseContextInterceptor interceptor = new SensitiveResponseContextInterceptor();
        SensitiveResponseBodyAdvice advice = new SensitiveResponseBodyAdvice(new SensitiveDataMasker());
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        TriggerOnlyController controller = new TriggerOnlyController();
        HandlerMethod handlerMethod = handlerMethod(TriggerOnlyController.class, controller, "triggerOnly");
        DemoDto dto = new DemoDto("13800138000");

        assertTrue(interceptor.preHandle(request, response, handlerMethod));
        SensitiveDataContext.record(dto, "phone", "13800138000", null);
        assertFalse(advice.supports(null, null));
        advice.beforeBodyWrite(dto, null, null, null, null, null);
        interceptor.afterCompletion(request, response, handlerMethod, null);

        assertEquals("13800138000", dto.phone);
        assertFalse(SensitiveDataContext.isActive());
    }

    /**
     * 测试目的：验证 controller 已开启脱敏上下文时，内部方法上的触发注解会实际消费该上下文并替换返回结果。
     * 测试场景：controller 方法使用 ANNOTATED_FIELDS 策略打开上下文，再调用带 SensitiveResponseTrigger 的代理 service，
     * 断言 service 返回 DTO 在离开方法时已经被脱敏，而不是只验证 advice 是否启用。
     */
    @Test
    void shouldMaskTriggeredServiceReturnValueWhenControllerScopeIsActive() throws Exception {
        SensitiveResponseContextInterceptor interceptor = new SensitiveResponseContextInterceptor();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        TriggerScopeController controller = new TriggerScopeController();
        HandlerMethod handlerMethod = handlerMethod(TriggerScopeController.class, controller, "annotatedMasking");
        TriggeredService service = proxiedTriggeredService();

        assertTrue(interceptor.preHandle(request, response, handlerMethod));
        DemoDto dto = service.loadView();
        interceptor.afterCompletion(request, response, handlerMethod, null);

        assertEquals("*******8000", dto.phone);
        assertFalse(SensitiveDataContext.isActive());
    }

    /**
     * 测试目的：验证敏感响应脱敏能按记录值、存储态脱敏列或自定义脱敏器选择正确来源。
     * 测试场景：构造控制器作用域、敏感字段注解和数据库脱敏值，断言响应写出前的脱敏结果和作用域释放顺序。
     */
    @Test
    void shouldReuseLikeAlgorithmForAnnotatedSensitiveField() {
        AlgorithmRegistry algorithmRegistry = new AlgorithmRegistry(
                Collections.emptyMap(),
                Collections.emptyMap(),
                Map.of("phoneMaskLike", new PhoneNumberMaskLikeQueryAlgorithm()));
        LikeAlgorithmDto dto = new LikeAlgorithmDto("13800138000");

        try (SensitiveDataContext.Scope ignored =
                     SensitiveDataContext.open(false, SensitiveResponseStrategy.ANNOTATED_FIELDS)) {
            new SensitiveDataMasker(null, algorithmRegistry).mask(dto);
        }

        assertEquals("*******8000", dto.phone);
    }

    /**
     * 测试目的：验证敏感响应脱敏能按记录值、存储态脱敏列或自定义脱敏器选择正确来源。
     * 测试场景：构造控制器作用域、敏感字段注解和数据库脱敏值，断言响应写出前的脱敏结果和作用域释放顺序。
     */
    @Test
    void shouldUseCustomSensitiveFieldMaskerWithAnnotationOptions() {
        CustomMaskerDto dto = new CustomMaskerDto("abcdefg");
        SensitiveDataMasker masker = new SensitiveDataMasker(
                null,
                null,
                Map.of("prefixMasker", (value, context) ->
                        context.option("prefix") + value.substring(value.length()
                                - Integer.parseInt(context.option("keepLast")))));

        try (SensitiveDataContext.Scope ignored =
                     SensitiveDataContext.open(false, SensitiveResponseStrategy.ANNOTATED_FIELDS)) {
            masker.mask(dto);
        }

        assertEquals("ID:efg", dto.secret);
    }

    private HandlerMethod handlerMethod(Object controller, String methodName) throws NoSuchMethodException {
        return handlerMethod(DemoController.class, controller, methodName);
    }

    private HandlerMethod handlerMethod(Class<?> controllerType, Object controller, String methodName)
            throws NoSuchMethodException {
        Method method = controllerType.getDeclaredMethod(methodName);
        return new HandlerMethod(controller, method);
    }

    private TriggeredService proxiedTriggeredService() {
        AspectJProxyFactory factory = new AspectJProxyFactory(new TriggeredService());
        factory.addAspect(new SensitiveResponseTriggerAspect(new SensitiveDataMasker()));
        return factory.getProxy();
    }

    static class DemoController {

        @SensitiveResponse
        DemoDto masked() {
            return null;
        }

        @SensitiveResponse(returnSensitive = true)
        DemoDto plain() {
            return null;
        }

        @SensitiveResponse
        StoredMaskDto maskedStored() {
            return null;
        }
    }

    @SensitiveResponse(returnSensitive = true)
    static class TriggeredController {

        @SensitiveResponseTrigger
        DemoDto triggeredMasked() {
            return null;
        }

        DemoDto inheritedPlain() {
            return null;
        }
    }

    static class TriggerOnlyController {

        @SensitiveResponseTrigger
        DemoDto triggerOnly() {
            return null;
        }
    }

    static class TriggerScopeController {

        @SensitiveResponse(strategy = SensitiveResponseStrategy.ANNOTATED_FIELDS)
        DemoDto annotatedMasking() {
            return null;
        }
    }

    static class TriggeredService {

        @SensitiveResponseTrigger
        DemoDto loadView() {
            return new DemoDto("13800138000");
        }
    }

    static class DemoDto {

        @SensitiveField
        private String phone;

        DemoDto(String phone) {
            this.phone = phone;
        }
    }

    static class StoredMaskDto {

        private String phone;

        StoredMaskDto(String phone) {
            this.phone = phone;
        }
    }

    static class LikeAlgorithmDto {

        @SensitiveField(likeAlgorithm = "phoneMaskLike")
        private String phone;

        LikeAlgorithmDto(String phone) {
            this.phone = phone;
        }
    }

    static class CustomMaskerDto {

        @SensitiveField(masker = "prefixMasker", options = {"prefix=ID:", "keepLast=3"})
        private String secret;

        CustomMaskerDto(String secret) {
            this.secret = secret;
        }
    }
}
