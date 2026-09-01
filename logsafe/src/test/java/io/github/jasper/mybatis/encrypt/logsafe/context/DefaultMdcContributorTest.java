package io.github.jasper.mybatis.encrypt.logsafe.context;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@Tag("unit")
@Tag("logsafe")
class DefaultMdcContributorTest {

    @Test
    void shouldContributeTraceAndEnabledOptionalKeys() {
        DefaultMdcContributor contributor = new DefaultMdcContributor(true, true, true);

        try {
            contributor.contribute(new TraceContext("trace-1", "request-1", "tenant-1", "user-1", "127.0.0.1"));

            assertEquals("trace-1", MDC.get("traceId"));
            assertEquals("request-1", MDC.get("requestId"));
            assertEquals("tenant-1", MDC.get("tenantId"));
            assertEquals("user-1", MDC.get("userId"));
            assertEquals("127.0.0.1", MDC.get("clientIp"));
        } finally {
            MDC.clear();
        }
    }

    @Test
    void shouldSkipDisabledAndBlankOptionalKeys() {
        DefaultMdcContributor contributor = new DefaultMdcContributor(false, true, false);

        try {
            contributor.contribute(new TraceContext("trace-1", "request-1", "tenant-1", " ", "127.0.0.1"));

            assertEquals("trace-1", MDC.get("traceId"));
            assertEquals("request-1", MDC.get("requestId"));
            assertNull(MDC.get("tenantId"));
            assertNull(MDC.get("userId"));
            assertNull(MDC.get("clientIp"));
        } finally {
            MDC.clear();
        }
    }
}
