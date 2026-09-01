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
        String content = identifier;
        if (content.startsWith(openQuote) && content.endsWith(closeQuote) && content.length() >= 2) {
            content = content.substring(openQuote.length(), content.length() - closeQuote.length());
        }
        String escaped = content.replace(openQuote, openQuote + openQuote);
        return openQuote + escaped + closeQuote;
    }
}
