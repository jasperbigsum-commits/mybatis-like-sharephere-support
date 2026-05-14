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

    /**
     * Creates an immutable trace context.
     *
     * @param traceId cross-service trace identifier
     * @param requestId per-request identifier
     * @param tenantId tenant identifier, may be {@code null}
     * @param userId user identifier, may be {@code null}
     * @param clientIp client IP address, may be {@code null}
     */
    public TraceContext(String traceId, String requestId, String tenantId, String userId, String clientIp) {
        this.traceId = traceId;
        this.requestId = requestId;
        this.tenantId = tenantId;
        this.userId = userId;
        this.clientIp = clientIp;
    }

    /**
     * Returns the cross-service trace identifier.
     *
     * @return trace identifier
     */
    public String getTraceId() {
        return traceId;
    }

    /**
     * Returns the per-request identifier.
     *
     * @return request identifier
     */
    public String getRequestId() {
        return requestId;
    }

    /**
     * Returns the tenant identifier.
     *
     * @return tenant identifier, or {@code null}
     */
    public String getTenantId() {
        return tenantId;
    }

    /**
     * Returns the user identifier.
     *
     * @return user identifier, or {@code null}
     */
    public String getUserId() {
        return userId;
    }

    /**
     * Returns the client IP address.
     *
     * @return client IP address, or {@code null}
     */
    public String getClientIp() {
        return clientIp;
    }
}
