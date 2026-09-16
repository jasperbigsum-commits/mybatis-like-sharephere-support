package io.github.jasper.mybatis.encrypt.core.mask;

import io.github.jasper.mybatis.encrypt.exception.EncryptionConfigurationException;
import io.github.jasper.mybatis.encrypt.exception.EncryptionErrorCode;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Access to an explicitly selected business field; inaccessible fields never silently pass. */
final class SensitiveFieldAccess {
    private SensitiveFieldAccess() { }

    static Object read(Field field, Object owner) {
        if (open(field)) {
            try { return field.get(owner); }
            catch (IllegalAccessException ex) { throw inaccessible(field); }
        }
        try {
            return accessor(field, owner, false).invoke(owner);
        } catch (ReflectiveOperationException | SecurityException ex) {
            throw inaccessible(field);
        }
    }

    static void write(Field field, Object owner, Object value) {
        if (Modifier.isFinal(field.getModifiers())) throw inaccessible(field);
        if (open(field)) {
            try { field.set(owner, value); return; }
            catch (IllegalAccessException ex) { throw inaccessible(field); }
        }
        try {
            accessor(field, owner, true).invoke(owner, value);
        } catch (ReflectiveOperationException | SecurityException ex) {
            throw inaccessible(field);
        }
    }

    // Java 8 compatible. A closed JPMS package can still expose exported public bean accessors.
    private static boolean open(Field field) {
        try { field.setAccessible(true); return true; }
        catch (RuntimeException denied) { return false; }
    }

    private static Method accessor(Field field, Object owner, boolean write) throws NoSuchMethodException {
        String name = field.getName();
        String suffix = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        Method method = write ? owner.getClass().getMethod("set" + suffix, field.getType())
                : owner.getClass().getMethod("get" + suffix);
        if (Modifier.isStatic(method.getModifiers())
                || (!write && !field.getType().isAssignableFrom(method.getReturnType()))) {
            throw new NoSuchMethodException();
        }
        return method;
    }

    static EncryptionConfigurationException inaccessible(Field field) {
        // No value, underlying getter exception or reflection/module diagnostics in the response.
        return new EncryptionConfigurationException(EncryptionErrorCode.INVALID_FIELD_RULE,
                "Cannot access selected sensitive response field: " + field.getDeclaringClass().getName()
                        + "." + field.getName() + "; expose public bean accessors or use an accessible DTO");
    }
}
