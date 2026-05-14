package io.github.jasper.mybatis.encrypt.logsafe.context;

/**
 * Immutable per-request trace context used for MDC contribution.
 */
public final class TraceContext {

    private final String traceId;
    private final String requestId;
    private final String tenantId;
    private final String userId;
    private final String clientIp;

    public TraceContext(String traceId, String requestId, String tenantId, String userId, String clientIp) {
        this.traceId = traceId;
        this.requestId = requestId;
        this.tenantId = tenantId;
        this.userId = userId;
        this.clientIp = clientIp;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getUserId() {
        return userId;
    }

    public String getClientIp() {
        return clientIp;
    }
}
