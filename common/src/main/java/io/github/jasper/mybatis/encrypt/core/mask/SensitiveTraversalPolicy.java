package io.github.jasper.mybatis.encrypt.core.mask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Explicit, immutable response DTO traversal boundary, shared by both Spring starters.
 *
 * <p>Unknown objects are opaque. SensitiveField-declaring classes are handled by the masker
 * automatically; register other DTO/wrapper classes or narrow DTO packages here. Registration
 * never grants access to fields declared by an unregistered superclass. Prefer adapters for
 * third-party wrappers: only the returned data is visited, using the wrapper's public API.</p>
 */
public final class SensitiveTraversalPolicy {
    private final List<String> packages;
    private final List<Class<?>> types;
    private final Map<Class<?>, Function<Object, Object>> adapters;

    private SensitiveTraversalPolicy(Builder builder) {
        packages = Collections.unmodifiableList(new ArrayList<String>(builder.packages));
        types = Collections.unmodifiableList(new ArrayList<Class<?>>(builder.types));
        adapters = Collections.unmodifiableMap(new LinkedHashMap<Class<?>, Function<Object, Object>>(builder.adapters));
    }

    /** Creates a builder; no arbitrary object types are allowed by default. */
    public static Builder builder() { return new Builder(); }

    boolean allows(Class<?> type) {
        if (types.contains(type)) return true;
        String name = type.getName();
        for (String prefix : packages) {
            if (name.startsWith(prefix + ".")) return true;
        }
        return false;
    }

    Function<Object, Object> adapter(Class<?> type) {
        for (Map.Entry<Class<?>, Function<Object, Object>> entry : adapters.entrySet()) {
            if (entry.getKey().isAssignableFrom(type)) return entry.getValue();
        }
        return null;
    }

    /** Registers only data models, never controller/service/framework implementation packages. */
    public static final class Builder {
        private final List<String> packages = new ArrayList<String>();
        private final List<Class<?>> types = new ArrayList<Class<?>>();
        private final Map<Class<?>, Function<Object, Object>> adapters =
                new LinkedHashMap<Class<?>, Function<Object, Object>>();

        /** Allows exact classes; subclasses and their own fields require separate registration. */
        public Builder allowTypes(Class<?>... allowed) {
            for (Class<?> type : allowed) types.add(Objects.requireNonNull(type, "type"));
            return this;
        }

        /** Allows fields declared in these packages and their subpackages. */
        public Builder allowPackages(String... allowed) {
            for (String name : allowed) {
                if (name == null || !name.matches("[a-zA-Z_$][\\w$]*(\\.[a-zA-Z_$][\\w$]*)+")) {
                    throw new IllegalArgumentException("Use a qualified DTO package without wildcards");
                }
                packages.add(name);
            }
            return this;
        }

        /** Visits only data returned by the adapter; no wrapper implementation fields are read. */
        public <T> Builder adapt(Class<T> type, Function<? super T, ?> data) {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(data, "data");
            adapters.put(type, value -> data.apply(type.cast(value)));
            return this;
        }

        /** Creates a reusable policy. Later builder changes do not affect the result. */
        public SensitiveTraversalPolicy build() { return new SensitiveTraversalPolicy(this); }
    }
}
