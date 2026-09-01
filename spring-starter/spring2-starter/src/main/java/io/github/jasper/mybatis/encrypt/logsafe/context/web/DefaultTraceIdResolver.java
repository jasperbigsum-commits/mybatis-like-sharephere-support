package io.github.jasper.mybatis.encrypt.logsafe.context.web;

import io.github.jasper.mybatis.encrypt.logsafe.context.TraceContext;
import io.github.jasper.mybatis.encrypt.util.StringUtils;
import javax.servlet.http.HttpServletRequest;

import java.util.UUID;

/**
 * Default HTTP trace-id resolver that reuses configured headers and generates missing identifiers.
 */
public class DefaultTraceIdResolver implements TraceIdResolver {

    private final String traceIdHeaderName;
    private final String requestIdHeaderName;

    /**
     * Creates a resolver using the supplied trace and request id header names.
     *
     * @param traceIdHeaderName header name used to read the trace id
     * @param requestIdHeaderName header name used to read the request id
     */
    public DefaultTraceIdResolver(String traceIdHeaderName, String requestIdHeaderName) {
        this.traceIdHeaderName = traceIdHeaderName;
        this.requestIdHeaderName = requestIdHeaderName;
    }

    @Override
    public TraceContext resolve(HttpServletRequest request) {
        String traceId = trimToNull(request.getHeader(traceIdHeaderName));
        String requestId = trimToNull(request.getHeader(requestIdHeaderName));
        if (traceId == null) {
            traceId = newId();
        }
        if (requestId == null) {
            requestId = newId();
        }
        return new TraceContext(traceId, requestId, null, null, null);
    }

    private String trimToNull(String value) {
        return StringUtils.isBlank(value) ? null : value.trim();
    }

    private String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
