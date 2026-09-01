package io.github.jasper.mybatis.encrypt.logsafe.context;

import org.slf4j.MDC;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Snapshot of the current MDC map for later restoration.
 */
public final class MdcSnapshot {

    private final Map<String, String> contextMap;

    private MdcSnapshot(Map<String, String> contextMap) {
        this.contextMap = contextMap;
    }

    /**
     * Captures the current MDC state.
     *
     * @return immutable snapshot
     */
    public static MdcSnapshot capture() {
        Map<String, String> current = MDC.getCopyOfContextMap();
        if (current == null || current.isEmpty()) {
            return new MdcSnapshot(Collections.<String, String>emptyMap());
        }
        return new MdcSnapshot(Collections.unmodifiableMap(new LinkedHashMap<>(current)));
    }

    /**
     * Restores the captured MDC state.
     */
    public void restore() {
        if (contextMap.isEmpty()) {
            MDC.clear();
            return;
        }
        MDC.setContextMap(contextMap);
    }

    /**
     * Returns the immutable captured MDC map.
     *
     * @return captured MDC context map
     */
    public Map<String, String> getContextMap() {
        return contextMap;
    }
}
