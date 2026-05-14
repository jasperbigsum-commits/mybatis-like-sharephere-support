package io.github.jasper.mybatis.encrypt.logsafe.context.web;

import io.github.jasper.mybatis.encrypt.logsafe.context.DefaultMdcContributor;
import io.github.jasper.mybatis.encrypt.logsafe.context.MdcSnapshot;
import io.github.jasper.mybatis.encrypt.logsafe.context.TraceContext;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
@Tag("web")
class TraceContextWebTest {

    @Test
    void shouldReuseIncomingTraceAndRequestIds() throws Exception {
        TraceContextInterceptor interceptor = new TraceContextInterceptor(
                new HeaderAwareTraceIdResolver("X-Trace-Id", "X-Request-Id"),
                new DefaultMdcContributor(false, false, false));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Trace-Id", "trace-from-gateway");
        request.addHeader("X-Request-Id", "request-from-gateway");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, handlerMethod()));
        assertEquals("trace-from-gateway", MDC.get("traceId"));
        assertEquals("request-from-gateway", MDC.get("requestId"));
        TraceContext context = (TraceContext) request.getAttribute(TraceContextInterceptor.TRACE_CONTEXT_ATTRIBUTE);
        assertNotNull(context);
        assertEquals("trace-from-gateway", context.getTraceId());
        assertEquals("request-from-gateway", context.getRequestId());

        interceptor.afterCompletion(request, response, handlerMethod(), null);
        assertNull(MDC.get("traceId"));
        assertNull(MDC.get("requestId"));
    }

    @Test
    void shouldGenerateIdsWhenHeadersAreMissingAndClearMdcAfterCompletion() throws Exception {
        TraceContextInterceptor interceptor = new TraceContextInterceptor(
                new DefaultTraceIdResolver("X-Trace-Id", "X-Request-Id"),
                new DefaultMdcContributor(false, false, false));
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, handlerMethod()));
        String traceId = MDC.get("traceId");
        String requestId = MDC.get("requestId");
        assertNotNull(traceId);
        assertNotNull(requestId);
        assertFalse(traceId.isEmpty());
        assertFalse(requestId.isEmpty());

        interceptor.afterCompletion(request, response, handlerMethod(), null);
        assertNull(MDC.get("traceId"));
        assertNull(MDC.get("requestId"));
    }

    @Test
    void shouldRestorePreviousMdcStateAfterCompletion() throws Exception {
        TraceContextInterceptor interceptor = new TraceContextInterceptor(
                new DefaultTraceIdResolver("X-Trace-Id", "X-Request-Id"),
                new DefaultMdcContributor(false, false, false));
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MDC.put("traceId", "outer-trace");
        MDC.put("custom", "keep-me");

        try {
            assertTrue(interceptor.preHandle(request, response, handlerMethod()));
            assertNotNull(MDC.get("requestId"));
            assertEquals("keep-me", MDC.get("custom"));

            interceptor.afterCompletion(request, response, handlerMethod(), null);

            assertEquals("outer-trace", MDC.get("traceId"));
            assertEquals("keep-me", MDC.get("custom"));
            assertNull(MDC.get("requestId"));
        } finally {
            MDC.clear();
        }
    }

    @Test
    void shouldClearContextWhenAsyncHandlingStarts() throws Exception {
        TraceContextInterceptor interceptor = new TraceContextInterceptor(
                new HeaderAwareTraceIdResolver("X-Trace-Id", "X-Request-Id"),
                new DefaultMdcContributor(false, false, false));
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, handlerMethod()));
        interceptor.afterConcurrentHandlingStarted(request, response, handlerMethod());

        assertNull(MDC.get("traceId"));
        assertNull(MDC.get("requestId"));
        assertNull(request.getAttribute(TraceContextInterceptor.TRACE_CONTEXT_ATTRIBUTE));
        assertNull(request.getAttribute(TraceContextInterceptor.MDC_SNAPSHOT_ATTRIBUTE));
    }

    private HandlerMethod handlerMethod() throws NoSuchMethodException {
        DemoController controller = new DemoController();
        Method method = DemoController.class.getDeclaredMethod("hello");
        return new HandlerMethod(controller, method);
    }

    static class DemoController {

        public String hello() {
            return "ok";
        }
    }

    static class HeaderAwareTraceIdResolver implements TraceIdResolver {

        private final String traceHeader;
        private final String requestHeader;

        HeaderAwareTraceIdResolver(String traceHeader, String requestHeader) {
            this.traceHeader = traceHeader;
            this.requestHeader = requestHeader;
        }

        @Override
        public TraceContext resolve(HttpServletRequest request) {
            String traceId = request.getHeader(traceHeader);
            String requestId = request.getHeader(requestHeader);
            return new TraceContext(traceId, requestId, null, null, null);
        }
    }
}
