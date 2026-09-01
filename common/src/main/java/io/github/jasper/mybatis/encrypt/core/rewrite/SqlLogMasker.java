package io.github.jasper.mybatis.encrypt.core.rewrite;

import java.util.Map;

/**
 * SQL 日志脱敏器。
 */
public class SqlLogMasker {

    /**
     * 将 SQL 与脱敏参数拼接成便于排查的日志文本。
     *
     * @param sql 原始或改写后的 SQL
     * @param maskedParameters 已脱敏的参数集合
     * @return 适合输出到日志的 SQL 文本
     */
    public String mask(String sql, Map<String, MaskedValue> maskedParameters) {
        if (sql == null) {
            return null;
        }
        if (maskedParameters == null || maskedParameters.isEmpty()) {
            return sql;
        }
        StringBuilder builder = new StringBuilder(sql.length() + maskedParameters.size() * 24);
        builder.append(sql).append(" /* params: ");
        boolean first = true;
        for (Map.Entry<String, MaskedValue> entry : maskedParameters.entrySet()) {
            if (!first) {
                builder.append(", ");
            }
            first = false;
            builder.append(entry.getKey()).append('=').append(entry.getValue().value());
        }
        builder.append(" */");
        return builder.toString();
    }
}
