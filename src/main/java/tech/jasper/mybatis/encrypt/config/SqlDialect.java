package tech.jasper.mybatis.encrypt.config;

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
        return openQuote + identifier + closeQuote;
    }
}
