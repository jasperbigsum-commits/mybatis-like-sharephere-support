package io.github.jasper.mybatis.encrypt.util;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;

/**
 * Centralized JSqlParser entry point.
 *
 * <p>MyBatis XML and dynamic SQL often contain repeated blank lines. Some JSqlParser versions are sensitive to those
 * gaps in specific grammar positions, so runtime parsing should go through this class instead of calling
 * {@link CCJSqlParserUtil} directly.</p>
 */
public final class JSqlParserSupport {

    private JSqlParserSupport() {
    }

    /**
     * Parses one SQL statement after applying parser-safe whitespace normalization.
     *
     * @param sql original SQL
     * @return parsed statement
     * @throws JSQLParserException when JSqlParser still cannot parse the normalized SQL
     */
    public static Statement parseStatement(String sql) throws JSQLParserException {
        return CCJSqlParserUtil.parse(normalizeSqlForParser(sql));
    }

    /**
     * Normalizes line separators and collapses repeated blank lines outside quoted text and SQL comments.
     *
     * @param sql original SQL
     * @return SQL suitable for JSqlParser
     */
    static String normalizeSqlForParser(String sql) {
        if (sql == null || sql.isEmpty()) {
            return sql;
        }
        String normalized = sql.replace("\r\n", "\n").replace('\r', '\n');
        if (!normalized.isEmpty() && normalized.charAt(0) == '\ufeff') {
            normalized = normalized.substring(1);
        }
        StringBuilder result = new StringBuilder(normalized.length());
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inBacktick = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        for (int index = 0; index < normalized.length(); index++) {
            char current = normalized.charAt(index);
            char next = index + 1 < normalized.length() ? normalized.charAt(index + 1) : '\0';

            if (inLineComment) {
                result.append(current);
                if (current == '\n') {
                    inLineComment = false;
                }
                continue;
            }
            if (inBlockComment) {
                result.append(current);
                if (current == '*' && next == '/') {
                    result.append(next);
                    index++;
                    inBlockComment = false;
                }
                continue;
            }
            if (inSingleQuote) {
                result.append(current);
                if (current == '\'' && next == '\'') {
                    result.append(next);
                    index++;
                } else if (current == '\'') {
                    inSingleQuote = false;
                }
                continue;
            }
            if (inDoubleQuote) {
                result.append(current);
                if (current == '"' && next == '"') {
                    result.append(next);
                    index++;
                } else if (current == '"') {
                    inDoubleQuote = false;
                }
                continue;
            }
            if (inBacktick) {
                result.append(current);
                if (current == '`') {
                    inBacktick = false;
                }
                continue;
            }

            if (current == '-' && next == '-') {
                result.append(current).append(next);
                index++;
                inLineComment = true;
                continue;
            }
            if (current == '#') {
                result.append(current);
                inLineComment = true;
                continue;
            }
            if (current == '/' && next == '*') {
                result.append(current).append(next);
                index++;
                inBlockComment = true;
                continue;
            }
            if (current == '\'') {
                result.append(current);
                inSingleQuote = true;
                continue;
            }
            if (current == '"') {
                result.append(current);
                inDoubleQuote = true;
                continue;
            }
            if (current == '`') {
                result.append(current);
                inBacktick = true;
                continue;
            }
            if (current == '\n') {
                result.append(current);
                index = skipRepeatedBlankLines(normalized, index + 1) - 1;
                continue;
            }
            result.append(current);
        }
        return result.toString();
    }

    private static int skipRepeatedBlankLines(String sql, int start) {
        int index = start;
        while (index < sql.length()) {
            int probe = index;
            while (probe < sql.length() && isHorizontalWhitespace(sql.charAt(probe))) {
                probe++;
            }
            if (probe < sql.length() && sql.charAt(probe) == '\n') {
                index = probe + 1;
                continue;
            }
            return index;
        }
        return index;
    }

    private static boolean isHorizontalWhitespace(char value) {
        return value == ' ' || value == '\t' || value == '\f';
    }
}
