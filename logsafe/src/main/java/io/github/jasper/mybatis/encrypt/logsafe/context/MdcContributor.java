package io.github.jasper.mybatis.encrypt.logsafe.context;

/**
 * Writes a resolved {@link TraceContext} into MDC keys.
 */
public interface MdcContributor {

    /**
     * Contributes the given context to MDC.
     *
     * @param context current trace context
     */
    void contribute(TraceContext context);
}
