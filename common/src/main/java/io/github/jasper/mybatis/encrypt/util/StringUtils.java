package io.github.jasper.mybatis.encrypt.util;

/**
 * 字符串辅助工具。
 */
public final class StringUtils {

    /**
     * 判断字符串是否为空
     * @param value 输入值
     * @return 是否为空
     */
    public static boolean isBlank(String value) {
        if (value == null) {
            return true;
        }
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isWhitespace(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断字符串是否不为空
     * @param value 输入值
     * @return 是否不为空
     */
    public static boolean isNotBlank(String value) {
        return !isBlank(value);
    }
}
