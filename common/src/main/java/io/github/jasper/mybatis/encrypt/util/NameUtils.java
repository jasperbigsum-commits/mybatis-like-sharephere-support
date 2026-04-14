package io.github.jasper.mybatis.encrypt.util;

import java.util.Locale;

/**
 * 命名辅助工具。
 *
 * <p>统一处理表名、列名和别名的规范化，以避免大小写、转义符差异造成规则匹配失败。</p>
 */
public final class NameUtils {

    private NameUtils() {
    }

    /**
     * 统一清洗数据库标识符，用于表名和列名匹配。
     *
     * @param raw 原始标识符
     * @return 规范化后的标识符
     */
    public static String normalizeIdentifier(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.replace("`", "")
                .replace("\"", "")
                .replace("[", "")
                .replace("]", "")
                .trim()
                .toLowerCase();
    }

    /**
     * 将驼峰名称转换为下划线名称。
     *
     * @param value 驼峰名称
     * @return 下划线风格名称
     */
    public static String camelToSnake(String value) {
        if (StringUtils.isBlank(value)) {
            return value;
        }
        StringBuilder builder = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isUpperCase(current) && index > 0) {
                builder.append('_');
            }
            builder.append(Character.toLowerCase(current));
        }
        return builder.toString();
    }

    /**
     * 将数据库列名推断为 Java 属性名。
     *
     * <p>优先处理下划线/中划线分词场景；当列名本身已接近驼峰形式时，仅把首字符降为小写。</p>
     *
     * @param value 数据库列名
     * @return 推断得到的属性名
     */
    public static String columnToProperty(String value) {
        if (StringUtils.isBlank(value)) {
            return value;
        }
        String cleaned = stripIdentifier(value).replace('-', '_');
        if (cleaned.indexOf('_') >= 0) {
            String[] parts = cleaned.split("_+");
            StringBuilder builder = new StringBuilder(cleaned.length());
            for (int index = 0; index < parts.length; index++) {
                String part = parts[index];
                if (part.isEmpty()) {
                    continue;
                }
                String lower = part.toLowerCase(Locale.ROOT);
                if (builder.length() == 0) {
                    builder.append(lower);
                    continue;
                }
                builder.append(Character.toUpperCase(lower.charAt(0)));
                if (lower.length() > 1) {
                    builder.append(lower.substring(1));
                }
            }
            return builder.toString();
        }
        if (cleaned.equals(cleaned.toUpperCase(Locale.ROOT))) {
            cleaned = cleaned.toLowerCase(Locale.ROOT);
        }
        return Character.toLowerCase(cleaned.charAt(0)) + cleaned.substring(1);
    }

    /**
     * 去除数据库标识符上的常见转义符。
     *
     * @param raw 原始标识符
     * @return 清洗后的标识符
     */
    public static String stripIdentifier(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.replace("`", "")
                .replace("\"", "")
                .replace("[", "")
                .replace("]", "")
                .trim();
    }
}
