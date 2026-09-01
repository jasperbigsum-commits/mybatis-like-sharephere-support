package io.github.jasper.mybatis.encrypt.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * 运行时对象遍历辅助工具。
 *
 * <p>统一收敛“是否为简单值类型”以及“把单对象/数组/集合视作顶层结果集合”这两类公共判断，
 * 避免解密链路和独立表回填链路各自维护一份相同逻辑。</p>
 */
public final class ObjectTraversalUtils {

    private ObjectTraversalUtils() {
    }

    /**
     * 判断给定类型是否应被视为简单值类型。
     *
     * @param type 候选类型
     * @return 简单值类型时返回 {@code true}
     */
    public static boolean isSimpleValueType(Class<?> type) {
        return type.isPrimitive()
                || type.isEnum()
                || CharSequence.class.isAssignableFrom(type)
                || Number.class.isAssignableFrom(type)
                || Boolean.class == type
                || Character.class == type
                || Date.class.isAssignableFrom(type)
                || java.time.temporal.Temporal.class.isAssignableFrom(type)
                || Class.class == type;
    }

    /**
     * 将查询返回对象统一包装为可遍历的顶层结果集合。
     *
     * @param value MyBatis 返回对象、数组或集合
     * @return 顶层结果集合视图
     */
    public static Collection<?> topLevelResults(Object value) {
        if (value == null) {
            return Collections.emptyList();
        }
        if (value instanceof Collection<?>) {
            return (Collection<?>) value;
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            List<Object> results = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                results.add(java.lang.reflect.Array.get(value, index));
            }
            return results;
        }
        return Collections.singletonList(value);
    }
}
