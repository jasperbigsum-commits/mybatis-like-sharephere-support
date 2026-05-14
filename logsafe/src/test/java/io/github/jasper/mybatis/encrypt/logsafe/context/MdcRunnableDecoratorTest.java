package io.github.jasper.mybatis.encrypt.logsafe.context;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@Tag("unit")
@Tag("logsafe")
class MdcRunnableDecoratorTest {

    @Test
    void shouldCaptureAndRestoreMdcSnapshot() {
        MDC.put("traceId", "trace-parent");
        MDC.put("requestId", "request-parent");
        MdcSnapshot snapshot = MdcSnapshot.capture();

        try {
            MDC.clear();
            snapshot.restore();

            assertEquals("trace-parent", MDC.get("traceId"));
            assertEquals("request-parent", MDC.get("requestId"));
        } finally {
            MDC.clear();
        }
    }

    @Test
    void shouldPropagateParentMdcIntoWorkerRunnable() {
        MdcRunnableDecorator decorator = new MdcRunnableDecorator();
        AtomicReference<String> traceId = new AtomicReference<String>();
        AtomicReference<String> requestId = new AtomicReference<String>();
        MDC.put("traceId", "trace-parent");
        MDC.put("requestId", "request-parent");

        try {
            Runnable decorated = decorator.decorate(new Runnable() {
                @Override
                public void run() {
                    traceId.set(MDC.get("traceId"));
                    requestId.set(MDC.get("requestId"));
                }
            });

            MDC.clear();
            decorated.run();
        } finally {
            MDC.clear();
        }

        assertEquals("trace-parent", traceId.get());
        assertEquals("request-parent", requestId.get());
    }

    @Test
    void shouldRestoreWorkerMdcAfterRunnable() {
        MdcRunnableDecorator decorator = new MdcRunnableDecorator();
        AtomicReference<String> insideTraceId = new AtomicReference<String>();
        MDC.put("traceId", "caller-trace");
        Runnable decorated = decorator.decorate(new Runnable() {
            @Override
            public void run() {
                insideTraceId.set(MDC.get("traceId"));
                MDC.put("traceId", "changed-inside");
            }
        });

        MDC.clear();
        MDC.put("traceId", "worker-trace");
        try {
            decorated.run();

            assertEquals("caller-trace", insideTraceId.get());
            assertEquals("worker-trace", MDC.get("traceId"));
        } finally {
            MDC.clear();
        }
    }

    @Test
    void shouldRestoreEmptyMdcWhenWorkerStartsEmpty() {
        MdcRunnableDecorator decorator = new MdcRunnableDecorator();
        AtomicReference<String> insideTraceId = new AtomicReference<String>();

        Runnable decorated = decorator.decorate(new Runnable() {
            @Override
            public void run() {
                insideTraceId.set(MDC.get("traceId"));
            }
        });
        decorated.run();

        assertNull(MDC.get("traceId"));
        assertNull(MDC.get("requestId"));
        assertNull(insideTraceId.get());
        MDC.clear();
    }
}
