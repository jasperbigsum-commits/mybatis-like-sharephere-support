package io.github.jasper.mybatis.encrypt.core.mask;

import io.github.jasper.mybatis.encrypt.config.SqlDialectContextHolder;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thread-bound response masking context opened by the web adapter around a controller invocation.
 *
 * <p>The context has two responsibilities. First, it tells the result decryptor whether decrypted
 * field values should be recorded for later response replacement. Second, it stores those recorded
 * values by owner object identity so the response layer can replace only the actual DTO instances
 * returned by the annotated controller.</p>
 *
 * <p>This class intentionally keeps state in a stack rather than a single slot. Nested controller or
 * framework invocations can therefore open independent scopes in the same thread without leaking
 * state into the outer invocation. Callers must close the returned {@link Scope}; the Spring MVC
 * interceptor does this in {@code afterCompletion} and when async handling starts.</p>
 */
public final class SensitiveDataContext {

    private static final ThreadLocal<Deque<Scope>> SCOPES = new ThreadLocal<Deque<Scope>>();

    private SensitiveDataContext() {
    }

    /**
     * Opens a controller response scope.
     *
     * @param returnSensitive whether the controller allows raw sensitive values
     * @param strategy masking strategy
     * @return closeable scope
     */
    public static Scope open(boolean returnSensitive, SensitiveResponseStrategy strategy) {
        Scope scope = new Scope(returnSensitive,
                strategy == null ? SensitiveResponseStrategy.RECORDED_ONLY : strategy);
        Deque<Scope> scopes = SCOPES.get();
        if (scopes == null) {
            scopes = new ArrayDeque<Scope>();
            SCOPES.set(scopes);
        }
        scopes.push(scope);
        return scope;
    }

    /**
     * Returns whether a masking scope is active.
     *
     * @return true when active
     */
    public static boolean isActive() {
        return current() != null;
    }

    /**
     * Returns whether decrypted values should be recorded for response masking.
     *
     * @return true when recording is needed
     */
    public static boolean isRecording() {
        Scope scope = current();
        return scope != null && !scope.returnSensitive;
    }

    /**
     * Returns whether the current response should be masked.
     *
     * @return true when masking is needed
     */
    public static boolean shouldMask() {
        Scope scope = current();
        return scope != null && !scope.returnSensitive;
    }

    /**
     * Returns the current masking strategy.
     *
     * @return current strategy
     */
    public static SensitiveResponseStrategy strategy() {
        Scope scope = current();
        return scope == null ? SensitiveResponseStrategy.RECORDED_ONLY : scope.strategy;
    }

    /**
     * Records a decrypted property value against the owning object reference.
     *
     * @param owner property owner
     * @param propertyName leaf property name
     * @param value decrypted value
     * @param rule encryption rule that produced the value
     */
    public static void record(Object owner, String propertyName, String value, EncryptColumnRule rule) {
        Scope scope = current();
        if (scope == null || scope.returnSensitive || owner == null || propertyName == null || value == null) {
            return;
        }
        scope.record(owner, propertyName, value, rule, SqlDialectContextHolder.currentDataSourceName());
    }

    /**
     * Returns all records in the current scope.
     *
     * @return records
     */
    public static Collection<SensitiveRecord> records() {
        Scope scope = current();
        return scope == null ? Collections.<SensitiveRecord>emptyList() : scope.records();
    }

    private static Scope current() {
        Deque<Scope> scopes = SCOPES.get();
        return scopes == null || scopes.isEmpty() ? null : scopes.peek();
    }

    /**
     * Closeable controller response scope.
     *
     * <p>The scope is idempotent: calling {@link #close()} multiple times is safe. When closed, it
     * removes itself from the current thread's scope stack and clears the {@link ThreadLocal} once
     * no scope remains.</p>
     */
    public static final class Scope implements AutoCloseable {

        private final boolean returnSensitive;
        private final SensitiveResponseStrategy strategy;
        private final IdentityHashMap<Object, Map<String, SensitiveRecord>> records = new IdentityHashMap<Object, Map<String, SensitiveRecord>>();
        private boolean closed;

        private Scope(boolean returnSensitive, SensitiveResponseStrategy strategy) {
            this.returnSensitive = returnSensitive;
            this.strategy = strategy;
        }

        /**
         * Returns whether raw values should be returned.
         *
         * @return raw response flag
         */
        public boolean returnSensitive() {
            return returnSensitive;
        }

        /**
         * Returns the response masking strategy.
         *
         * @return strategy
         */
        public SensitiveResponseStrategy strategy() {
            return strategy;
        }

        private void record(Object owner,
                            String propertyName,
                            String value,
                            EncryptColumnRule rule,
                            String dataSourceName) {
            Map<String, SensitiveRecord> ownerRecords = records.get(owner);
            if (ownerRecords == null) {
                ownerRecords = new LinkedHashMap<String, SensitiveRecord>();
                records.put(owner, ownerRecords);
            }
            ownerRecords.put(propertyName, new SensitiveRecord(owner, propertyName, value, rule, dataSourceName));
        }

        private Collection<SensitiveRecord> records() {
            ArrayList<SensitiveRecord> values = new ArrayList<SensitiveRecord>();
            for (Map<String, SensitiveRecord> ownerRecords : records.values()) {
                values.addAll(ownerRecords.values());
            }
            return Collections.unmodifiableList(values);
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            Deque<Scope> scopes = SCOPES.get();
            if (scopes != null && !scopes.isEmpty() && scopes.peek() == this) {
                scopes.pop();
            } else if (scopes != null) {
                scopes.remove(this);
            }
            if (scopes == null || scopes.isEmpty()) {
                SCOPES.remove();
            }
        }
    }

    /**
     * One decrypted property captured for possible response replacement.
     *
     * <p>The {@code owner} field is the exact DTO object reference that currently contains the
     * decrypted value. The response masker uses that identity to avoid replacing a different object
     * that happens to have the same field name and value.</p>
     */
    public static final class SensitiveRecord {

        private final Object owner;
        private final String propertyName;
        private final String value;
        private final EncryptColumnRule rule;
        private final String dataSourceName;

        private SensitiveRecord(Object owner,
                                String propertyName,
                                String value,
                                EncryptColumnRule rule,
                                String dataSourceName) {
            this.owner = owner;
            this.propertyName = propertyName;
            this.value = value;
            this.rule = rule;
            this.dataSourceName = dataSourceName;
        }

        /**
         * Returns the exact object instance that currently holds the decrypted value.
         *
         * @return DTO or entity instance captured during result handling
         */
        public Object owner() {
            return owner;
        }

        /**
         * Returns the leaf property name on {@link #owner()} that should be replaced.
         *
         * @return property name
         */
        public String propertyName() {
            return propertyName;
        }

        /**
         * Returns the decrypted clear-text value captured from the result object.
         *
         * @return decrypted value
         */
        public String value() {
            return value;
        }

        /**
         * Returns the encryption rule that produced the decrypted value, when available.
         *
         * @return column rule or {@code null}
         */
        public EncryptColumnRule rule() {
            return rule;
        }

        /**
         * Returns the datasource name active when the value was recorded.
         *
         * @return datasource name, may be {@code null}
         */
        public String dataSourceName() {
            return dataSourceName;
        }
    }
}
