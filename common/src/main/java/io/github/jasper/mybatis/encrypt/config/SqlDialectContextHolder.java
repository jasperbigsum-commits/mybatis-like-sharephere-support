package io.github.jasper.mybatis.encrypt.config;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 记录当前执行线程所绑定的数据源名称，供 SQL 方言解析与多数据源路由复用。
 */
public final class SqlDialectContextHolder {

    private static final String NULL_DATASOURCE = "__NULL_DATASOURCE__";
    private static final ThreadLocal<Deque<String>> DATASOURCE_NAMES =
            new ThreadLocal<Deque<String>>();

    private SqlDialectContextHolder() {
    }

    /**
     * 打开一次数据源上下文作用域。
     *
     * @param dataSourceName 当前执行使用的数据源名称
     * @return 关闭当前作用域的句柄
     */
    public static Scope open(String dataSourceName) {
        Deque<String> stack = DATASOURCE_NAMES.get();
        if (stack == null) {
            stack = new ArrayDeque<String>();
            DATASOURCE_NAMES.set(stack);
        }
        stack.push(dataSourceName == null ? NULL_DATASOURCE : dataSourceName);
        return new Scope();
    }

    /**
     * 返回当前线程上下文中的数据源名称。
     *
     * @return 当前数据源名称；未绑定时返回 {@code null}
     */
    public static String currentDataSourceName() {
        Deque<String> stack = DATASOURCE_NAMES.get();
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        String current = stack.peek();
        return NULL_DATASOURCE.equals(current) ? null : current;
    }

    /**
     * 作用域关闭句柄。
     */
    public static final class Scope implements AutoCloseable {

        private boolean closed;

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            Deque<String> stack = DATASOURCE_NAMES.get();
            if (stack == null || stack.isEmpty()) {
                DATASOURCE_NAMES.remove();
                return;
            }
            stack.pop();
            if (stack.isEmpty()) {
                DATASOURCE_NAMES.remove();
            }
        }
    }
}
