package io.github.jasper.mybatis.encrypt.logsafe.context.web;

import io.github.jasper.mybatis.encrypt.logsafe.context.MdcContributor;
import io.github.jasper.mybatis.encrypt.logsafe.context.MdcSnapshot;
import io.github.jasper.mybatis.encrypt.logsafe.context.TraceContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

/**
 * Opens request-scoped trace MDC entries and restores the previous MDC state on completion.
 */
public class TraceContextInterceptor implements AsyncHandlerInterceptor {

    public static final String TRACE_CONTEXT_ATTRIBUTE = TraceContextInterceptor.class.getName() + ".TRACE_CONTEXT";
    public static final String MDC_SNAPSHOT_ATTRIBUTE = TraceContextInterceptor.class.getName() + ".MDC_SNAPSHOT";

    private final TraceIdResolver traceIdResolver;
    private final MdcContributor mdcContributor;

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
