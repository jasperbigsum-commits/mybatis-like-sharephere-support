package io.github.jasper.mybatis.encrypt.logsafe.context.web;

import io.github.jasper.mybatis.encrypt.logsafe.context.MdcContributor;
import io.github.jasper.mybatis.encrypt.logsafe.context.MdcSnapshot;
import io.github.jasper.mybatis.encrypt.logsafe.context.TraceContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

/**
 * Opens request-scoped trace MDC entries and restores the previous MDC state on completion.
 */
public class TraceContextInterceptor implements AsyncHandlerInterceptor {

    /**
     * Request attribute key that stores the resolved trace context for the current request.
     */
    public static final String TRACE_CONTEXT_ATTRIBUTE = TraceContextInterceptor.class.getName() + ".TRACE_CONTEXT";

    /**
     * Request attribute key that stores the MDC snapshot captured before request handling.
     */
    public static final String MDC_SNAPSHOT_ATTRIBUTE = TraceContextInterceptor.class.getName() + ".MDC_SNAPSHOT";

    private final TraceIdResolver traceIdResolver;
    private final MdcContributor mdcContributor;

    /**
     * Creates an interceptor with the supplied trace resolver and MDC contributor.
     *
     * @param traceIdResolver trace identifier resolver
     * @param mdcContributor MDC contributor
     */
    public TraceContextInterceptor(TraceIdResolver traceIdResolver, MdcContributor mdcContributor) {
        this.traceIdResolver = traceIdResolver;
        this.mdcContributor = mdcContributor;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        MdcSnapshot snapshot = MdcSnapshot.capture();
        TraceContext context = traceIdResolver.resolve(request);
        request.setAttribute(MDC_SNAPSHOT_ATTRIBUTE, snapshot);
        request.setAttribute(TRACE_CONTEXT_ATTRIBUTE, context);
        mdcContributor.contribute(context);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        restore(request);
    }

    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response, Object handler) {
        restore(request);
    }

    private void restore(HttpServletRequest request) {
        Object snapshot = request.getAttribute(MDC_SNAPSHOT_ATTRIBUTE);
        if (snapshot instanceof MdcSnapshot) {
            ((MdcSnapshot) snapshot).restore();
        }
        request.removeAttribute(MDC_SNAPSHOT_ATTRIBUTE);
        request.removeAttribute(TRACE_CONTEXT_ATTRIBUTE);
    }
}
