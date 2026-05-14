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

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public ContextProperties getContext() {
        return context;
    }

    public void setContext(ContextProperties context) {
        this.context = context == null ? new ContextProperties() : context;
    }

    public TerminalProperties getTerminal() {
        return terminal;
    }

    public void setTerminal(TerminalProperties terminal) {
        this.terminal = terminal == null ? new TerminalProperties() : terminal;
    }

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

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public HeaderProperties getHeader() {
            return header;
        }

        public void setHeader(HeaderProperties header) {
            this.header = header == null ? new HeaderProperties() : header;
        }

        public KeyProperties getKeys() {
            return keys;
        }

        public void setKeys(KeyProperties keys) {
            this.keys = keys == null ? new KeyProperties() : keys;
        }

        public PropagationProperties getPropagation() {
            return propagation;
        }

        public void setPropagation(PropagationProperties propagation) {
            this.propagation = propagation == null ? new PropagationProperties() : propagation;
        }
    }

    public static class HeaderProperties {

        private String traceId = "X-Trace-Id";
        private String requestId = "X-Request-Id";

        public String getTraceId() {
            return traceId;
        }

        public void setTraceId(String traceId) {
            this.traceId = traceId;
        }

        public String getRequestId() {
            return requestId;
        }

        public void setRequestId(String requestId) {
            this.requestId = requestId;
        }
    }

    public static class KeyProperties {

        private boolean tenantIdEnabled;
        private boolean userIdEnabled;
        private boolean clientIpEnabled;

        public boolean isTenantIdEnabled() {
            return tenantIdEnabled;
        }

        public void setTenantIdEnabled(boolean tenantIdEnabled) {
            this.tenantIdEnabled = tenantIdEnabled;
        }

        public boolean isUserIdEnabled() {
            return userIdEnabled;
        }

        public void setUserIdEnabled(boolean userIdEnabled) {
            this.userIdEnabled = userIdEnabled;
        }

        public boolean isClientIpEnabled() {
            return clientIpEnabled;
        }

        public void setClientIpEnabled(boolean clientIpEnabled) {
            this.clientIpEnabled = clientIpEnabled;
        }
    }

    public static class PropagationProperties {

        private boolean asyncEnabled = true;

        public boolean isAsyncEnabled() {
            return asyncEnabled;
        }

        public void setAsyncEnabled(boolean asyncEnabled) {
            this.asyncEnabled = asyncEnabled;
        }
    }

    public static class TerminalProperties {

        /**
         * Enables platform-specific terminal log masking adapters when the logging backend exists.
         */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
