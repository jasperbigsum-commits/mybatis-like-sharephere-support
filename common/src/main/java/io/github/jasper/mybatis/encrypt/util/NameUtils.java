package io.github.jasper.mybatis.encrypt.util;

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
}
