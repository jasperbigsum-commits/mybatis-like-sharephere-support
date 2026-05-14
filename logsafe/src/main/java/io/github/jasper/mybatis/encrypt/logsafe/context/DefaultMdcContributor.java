package io.github.jasper.mybatis.encrypt.logsafe.context;

import io.github.jasper.mybatis.encrypt.util.StringUtils;
import org.slf4j.MDC;

/**
 * Default MDC contributor for trace and optional request-side keys.
 */
public class DefaultMdcContributor implements MdcContributor {

    private final boolean tenantIdEnabled;
    private final boolean userIdEnabled;
    private final boolean clientIpEnabled;

    public DefaultMdcContributor(boolean tenantIdEnabled, boolean userIdEnabled, boolean clientIpEnabled) {
        this.tenantIdEnabled = tenantIdEnabled;
        this.userIdEnabled = userIdEnabled;
        this.clientIpEnabled = clientIpEnabled;
    }

    @Override
    public void contribute(TraceContext context) {
        putIfNotBlank("traceId", context.getTraceId());
        putIfNotBlank("requestId", context.getRequestId());
        if (tenantIdEnabled) {
            putIfNotBlank("tenantId", context.getTenantId());
        }
        if (userIdEnabled) {
            putIfNotBlank("userId", context.getUserId());
        }
        if (clientIpEnabled) {
            putIfNotBlank("clientIp", context.getClientIp());
        }
    }

    private void putIfNotBlank(String key, String value) {
        if (StringUtils.isNotBlank(value)) {
            MDC.put(key, value);
        }
    }
}
