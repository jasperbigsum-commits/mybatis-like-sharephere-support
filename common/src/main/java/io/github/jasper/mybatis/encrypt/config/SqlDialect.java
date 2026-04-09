package io.github.jasper.mybatis.encrypt.config;

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
