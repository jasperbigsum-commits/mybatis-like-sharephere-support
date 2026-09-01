package io.github.jasper.mybatis.encrypt.logsafe.context.web;

import io.github.jasper.mybatis.encrypt.logsafe.context.TraceContext;
import javax.servlet.http.HttpServletRequest;

/**
 * Resolves request-scoped trace identifiers from an HTTP request.
 */
public interface TraceIdResolver {

    /**
     * Resolves a trace context from the current request.
     *
     * @param request current HTTP request
     * @return resolved context
     */
    TraceContext resolve(HttpServletRequest request);
}
