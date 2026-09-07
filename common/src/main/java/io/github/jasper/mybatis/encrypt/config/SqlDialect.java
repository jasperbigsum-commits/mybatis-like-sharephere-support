package io.github.jasper.mybatis.encrypt.config;

import io.github.jasper.mybatis.encrypt.util.StringUtils;

/**
 * SQL 方言。
 */
public enum SqlDialect {
    /**
     * MySQL 风格反引号。
     */
    MYSQL("`", "`"),
    /**
     * OceanBase MySQL 模式反引号。
     */
    OCEANBASE("`", "`"),
    /**
     * 达梦双引号。
     */
    DM("\"", "\""),
    /**
     * Oracle 12 双引号。
     */
    ORACLE12("\"", "\""),
    /**
     * ClickHouse 反引号。
     */
    CLICKHOUSE("`", "`");

    private final String openQuote;
    private final String closeQuote;

    SqlDialect(String openQuote, String closeQuote) {
        this.openQuote = openQuote;
        this.closeQuote = closeQuote;
    }

    /**
     * 为标识符添加当前方言需要的引用符。
     *
     * @param identifier 原始标识符
     * @return 引用后的标识符
     */
    public String quote(String identifier) {
        if (StringUtils.isBlank(identifier)) {
            return identifier;
        }
        // Schema-qualified names must quote each component separately. Quoting the whole
        // value would address an object literally named "SCHEMA.TABLE" on DM/Oracle.
        if (containsUnquotedDot(identifier)) {
            StringBuilder result = new StringBuilder(identifier.length() + 4);
            int start = 0;
            boolean inQuoted = false;
            for (int index = 0; index < identifier.length(); index++) {
                char current = identifier.charAt(index);
                if (current == openQuote.charAt(0)) {
                    inQuoted = !inQuoted;
                } else if (current == '.' && !inQuoted) {
                    result.append(quote(identifier.substring(start, index))).append('.');
                    start = index + 1;
                }
            }
            result.append(quote(identifier.substring(start)));
            return result.toString();
        }
        String content = identifier;
        if (content.startsWith(openQuote) && content.endsWith(closeQuote) && content.length() >= 2) {
            content = content.substring(openQuote.length(), content.length() - closeQuote.length());
        }
        String escaped = content.replace(openQuote, openQuote + openQuote);
        return openQuote + escaped + closeQuote;
    }

    private boolean containsUnquotedDot(String identifier) {
        boolean inQuoted = false;
        for (int index = 0; index < identifier.length(); index++) {
            char current = identifier.charAt(index);
            if (current == openQuote.charAt(0)) {
                inQuoted = !inQuoted;
            } else if (current == '.' && !inQuoted) {
                return true;
            }
        }
        return false;
    }

    /**
     * 输出迁移批量读取所需的分页尾子句。
     *
     * @param placeholder 参数占位符
     * @return 当前方言分页语法
     */
    public String renderFetchFirst(String placeholder) {
        return this == ORACLE12 || this == DM
                ? " fetch first " + placeholder + " rows only"
                : " limit " + placeholder;
    }

    /**
     * 判断插件会识别的数据库函数是否可直接交给当前数据库执行。
     * 未列出的业务自定义函数不在这里拦截，避免把方言判断扩大成 SQL 白名单。
     *
     * @param functionName 函数名
     * @return 当前方言支持时返回 {@code true}
     */
    public boolean supportsPluginFunction(String functionName) {
        if (StringUtils.isBlank(functionName)) {
            return true;
        }
        String name = functionName.trim().toUpperCase(java.util.Locale.ROOT);
        if ("GROUP_CONCAT".equals(name) || "FIND_IN_SET".equals(name)
                || "JSON_EXTRACT".equals(name) || "REGEXP".equals(name)) {
            return this == MYSQL || this == OCEANBASE;
        }
        if ("LISTAGG".equals(name)) {
            return this != CLICKHOUSE;
        }
        return true;
    }
}
