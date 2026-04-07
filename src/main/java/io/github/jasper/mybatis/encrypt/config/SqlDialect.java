package io.github.jasper.mybatis.encrypt.config;

/**
 * SQL 方言。
 */
public enum SqlDialect {
    MYSQL("`", "`"),
    OCEANBASE("`", "`"),
    DM("\"", "\"");

    private final String openQuote;
    private final String closeQuote;

    SqlDialect(String openQuote, String closeQuote) {
        this.openQuote = openQuote;
        this.closeQuote = closeQuote;
    }

    public String quote(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return identifier;
        }
        // 避免对已引用的标识符重复添加引号
        if (identifier.startsWith(openQuote) && identifier.endsWith(closeQuote)) {
            return identifier;
        }
        return openQuote + identifier + closeQuote;
    }
}
