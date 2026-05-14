package io.github.jasper.mybatis.encrypt.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * External configuration model for logsafe logging and MDC context support.
 *
 * <p>This dedicated properties tree exists so IDEs and Spring Boot metadata generation can expose
 * logsafe-specific configuration keys independently from the main encryption properties.</p>
 */
@ConfigurationProperties(prefix = "mybatis.encrypt.logsafe")
public class LogsafeProperties {

    /**
     * Top-level logsafe enable switch.
     */
    private boolean enabled = true;

    /**
     * Request-context and MDC related settings.
     */
    private ContextProperties context = new ContextProperties();

    /**
     * Terminal logging safety-net settings.
     */
    private TerminalProperties terminal = new TerminalProperties();

    /**
     * Returns whether logsafe auto-configuration is enabled.
     *
     * @return {@code true} when logsafe support should be active
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Sets whether logsafe auto-configuration is enabled.
     *
     * @param enabled {@code true} to enable logsafe support
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Returns request-context and MDC settings.
     *
     * @return context properties
     */
    public ContextProperties getContext() {
        return context;
    }

    /**
     * Sets request-context and MDC settings.
     *
     * @param context context properties, or {@code null} to restore defaults
     */
    public void setContext(ContextProperties context) {
        this.context = context == null ? new ContextProperties() : context;
    }

    /**
     * Returns terminal logging settings.
     *
     * @return terminal properties
     */
    public TerminalProperties getTerminal() {
        return terminal;
    }

    /**
     * Sets terminal logging settings.
     *
     * @param terminal terminal properties, or {@code null} to restore defaults
     */
    public void setTerminal(TerminalProperties terminal) {
        this.terminal = terminal == null ? new TerminalProperties() : terminal;
    }

    /**
     * Configuration for request-context capture and MDC propagation.
     */
    public static class ContextProperties {

        /**
         * Enables MVC request MDC integration.
         */
        private boolean enabled = true;

        /**
         * Header names used to resolve incoming trace identifiers.
         */
        private HeaderProperties header = new HeaderProperties();

        /**
         * Optional MDC key toggles.
         */
        private KeyProperties keys = new KeyProperties();

        /**
         * Async propagation settings.
         */
        private PropagationProperties propagation = new PropagationProperties();

        /**
         * Returns whether MVC request MDC integration is enabled.
         *
         * @return {@code true} when request context should be captured
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * Sets whether MVC request MDC integration is enabled.
         *
         * @param enabled {@code true} to capture request context
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * Returns header-name settings for inbound trace context.
         *
         * @return header properties
         */
        public HeaderProperties getHeader() {
            return header;
        }

        /**
         * Sets header-name settings for inbound trace context.
         *
         * @param header header properties, or {@code null} to restore defaults
         */
        public void setHeader(HeaderProperties header) {
            this.header = header == null ? new HeaderProperties() : header;
        }

        /**
         * Returns optional MDC key settings.
         *
         * @return MDC key properties
         */
        public KeyProperties getKeys() {
            return keys;
        }

        /**
         * Sets optional MDC key settings.
         *
         * @param keys key properties, or {@code null} to restore defaults
         */
        public void setKeys(KeyProperties keys) {
            this.keys = keys == null ? new KeyProperties() : keys;
        }

        /**
         * Returns async MDC propagation settings.
         *
         * @return propagation properties
         */
        public PropagationProperties getPropagation() {
            return propagation;
        }

        /**
         * Sets async MDC propagation settings.
         *
         * @param propagation propagation properties, or {@code null} to restore defaults
         */
        public void setPropagation(PropagationProperties propagation) {
            this.propagation = propagation == null ? new PropagationProperties() : propagation;
        }
    }

    /**
     * Header names used to read trace identifiers from incoming requests.
     */
    public static class HeaderProperties {

        private String traceId = "X-Trace-Id";
        private String requestId = "X-Request-Id";

        /**
         * Returns the incoming trace-id header name.
         *
         * @return trace-id header name
         */
        public String getTraceId() {
            return traceId;
        }

        /**
         * Sets the incoming trace-id header name.
         *
         * @param traceId trace-id header name
         */
        public void setTraceId(String traceId) {
            this.traceId = traceId;
        }

        /**
         * Returns the incoming request-id header name.
         *
         * @return request-id header name
         */
        public String getRequestId() {
            return requestId;
        }

        /**
         * Sets the incoming request-id header name.
         *
         * @param requestId request-id header name
         */
        public void setRequestId(String requestId) {
            this.requestId = requestId;
        }
    }

    /**
     * Toggles for optional MDC keys that may identify tenant, user, or client origin.
     */
    public static class KeyProperties {

        private boolean tenantIdEnabled;
        private boolean userIdEnabled;
        private boolean clientIpEnabled;

        /**
         * Returns whether tenant-id MDC output is enabled.
         *
         * @return {@code true} when tenant id should be written to MDC
         */
        public boolean isTenantIdEnabled() {
            return tenantIdEnabled;
        }

        /**
         * Sets whether tenant-id MDC output is enabled.
         *
         * @param tenantIdEnabled {@code true} to write tenant id to MDC
         */
        public void setTenantIdEnabled(boolean tenantIdEnabled) {
            this.tenantIdEnabled = tenantIdEnabled;
        }

        /**
         * Returns whether user-id MDC output is enabled.
         *
         * @return {@code true} when user id should be written to MDC
         */
        public boolean isUserIdEnabled() {
            return userIdEnabled;
        }

        /**
         * Sets whether user-id MDC output is enabled.
         *
         * @param userIdEnabled {@code true} to write user id to MDC
         */
        public void setUserIdEnabled(boolean userIdEnabled) {
            this.userIdEnabled = userIdEnabled;
        }

        /**
         * Returns whether client-ip MDC output is enabled.
         *
         * @return {@code true} when client IP should be written to MDC
         */
        public boolean isClientIpEnabled() {
            return clientIpEnabled;
        }

        /**
         * Sets whether client-ip MDC output is enabled.
         *
         * @param clientIpEnabled {@code true} to write client IP to MDC
         */
        public void setClientIpEnabled(boolean clientIpEnabled) {
            this.clientIpEnabled = clientIpEnabled;
        }
    }

    /**
     * Configuration for propagating MDC context across asynchronous execution.
     */
    public static class PropagationProperties {

        private boolean asyncEnabled = true;

        /**
         * Returns whether async MDC propagation is enabled.
         *
         * @return {@code true} when async propagation should be configured
         */
        public boolean isAsyncEnabled() {
            return asyncEnabled;
        }

        /**
         * Sets whether async MDC propagation is enabled.
         *
         * @param asyncEnabled {@code true} to configure async propagation
         */
        public void setAsyncEnabled(boolean asyncEnabled) {
            this.asyncEnabled = asyncEnabled;
        }
    }

    /**
     * Configuration for terminal logging safety adapters.
     */
    public static class TerminalProperties {

        /**
         * Enables platform-specific terminal log masking adapters when the logging backend exists.
         */
        private boolean enabled = true;

        /**
         * Returns whether terminal log masking adapters are enabled.
         *
         * @return {@code true} when terminal adapters should be configured
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * Sets whether terminal log masking adapters are enabled.
         *
         * @param enabled {@code true} to configure terminal adapters
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
