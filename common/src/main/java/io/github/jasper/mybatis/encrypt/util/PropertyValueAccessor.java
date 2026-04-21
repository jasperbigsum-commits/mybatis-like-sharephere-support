package io.github.jasper.mybatis.encrypt.util;

import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Field-first property accessor used by infrastructure code to avoid invoking business getters.
 */
public final class PropertyValueAccessor {

    private static final ConcurrentMap<FieldKey, Optional<Field>> FIELD_CACHE =
            new ConcurrentHashMap<FieldKey, Optional<Field>>();

    /**
     * Resolves a property reference.
     *
     * @param root root object
     * @param propertyPath dot-separated property path
     * @return property reference, or null when it cannot be resolved
     */
    public PropertyReference resolve(Object root, String propertyPath) {
        if (root == null || StringUtils.isBlank(propertyPath)) {
            return null;
        }
        String[] parts = propertyPath.split("\\.");
        Object owner = root;
        for (int index = 0; index < parts.length - 1; index++) {
            owner = read(owner, parts[index]);
            if (owner == null) {
                return null;
            }
        }
        String propertyName = parts[parts.length - 1];
        Field field = findField(owner.getClass(), propertyName).orElse(null);
        return new PropertyReference(owner, propertyName, field);
    }

    private Object read(Object owner, String propertyName) {
        if (owner == null || StringUtils.isBlank(propertyName)) {
            return null;
        }
        Field field = findField(owner.getClass(), propertyName).orElse(null);
        if (field != null) {
            try {
                return field.get(owner);
            } catch (IllegalAccessException ex) {
                return null;
            }
        }
        MetaObject metaObject = SystemMetaObject.forObject(owner);
        return metaObject.hasGetter(propertyName) ? metaObject.getValue(propertyName) : null;
    }

    private static Optional<Field> findField(Class<?> type, String propertyName) {
        if (type == null || StringUtils.isBlank(propertyName)) {
            return Optional.empty();
        }
        FieldKey key = new FieldKey(type, propertyName);
        Optional<Field> cached = FIELD_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        Optional<Field> resolved = resolveField(type, propertyName);
        Optional<Field> previous = FIELD_CACHE.putIfAbsent(key, resolved);
        return previous == null ? resolved : previous;
    }

    private static Optional<Field> resolveField(Class<?> type, String propertyName) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            try {
                Field field = current.getDeclaredField(propertyName);
                if (Modifier.isStatic(field.getModifiers())) {
                    return Optional.empty();
                }
                field.setAccessible(true);
                return Optional.of(field);
            } catch (NoSuchFieldException ex) {
                current = current.getSuperclass();
            }
        }
        return Optional.empty();
    }

    /**
     * Field-first reference to a property on an owner object.
     */
    public static final class PropertyReference {

        private final Object owner;
        private final String propertyName;
        private final Field field;

        private PropertyReference(Object owner, String propertyName, Field field) {
            this.owner = owner;
            this.propertyName = propertyName;
            this.field = field;
        }

        /**
         * 获取归属对象
         * @return 归属对象
         */
        public Object owner() {
            return owner;
        }

        /**
         * 属性字段名称
         * @return 属性名称
         */
        public String propertyName() {
            return propertyName;
        }

        /**
         * 获取引用对象的值
         * @return 引用对象的值
         */
        public Object getValue() {
            if (field != null) {
                try {
                    return field.get(owner);
                } catch (IllegalAccessException ex) {
                    return null;
                }
            }
            MetaObject metaObject = SystemMetaObject.forObject(owner);
            return metaObject.hasGetter(propertyName) ? metaObject.getValue(propertyName) : null;
        }

        /**
         * 是否可写
         * @return 是否可写
         */
        public boolean canWrite() {
            if (field != null) {
                return !Modifier.isFinal(field.getModifiers());
            }
            return SystemMetaObject.forObject(owner).hasSetter(propertyName);
        }

        /**
         * 设置属性值
         * @param value 值内容
         * @return 是否成功
         */
        public boolean setValue(Object value) {
            if (field != null) {
                if (Modifier.isFinal(field.getModifiers())) {
                    return false;
                }
                try {
                    field.set(owner, value);
                    return true;
                } catch (IllegalAccessException ex) {
                    return false;
                }
            }
            MetaObject metaObject = SystemMetaObject.forObject(owner);
            if (!metaObject.hasSetter(propertyName)) {
                return false;
            }
            metaObject.setValue(propertyName, value);
            return true;
        }
    }

    private static final class FieldKey {

        private final Class<?> type;
        private final String propertyName;

        private FieldKey(Class<?> type, String propertyName) {
            this.type = type;
            this.propertyName = propertyName;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof FieldKey)) {
                return false;
            }
            FieldKey that = (FieldKey) other;
            return type.equals(that.type) && propertyName.equals(that.propertyName);
        }

        @Override
        public int hashCode() {
            int result = type.hashCode();
            result = 31 * result + propertyName.hashCode();
            return result;
        }
    }
}
