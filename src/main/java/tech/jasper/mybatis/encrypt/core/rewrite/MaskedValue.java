package tech.jasper.mybatis.encrypt.core.rewrite;

/**
 * 日志脱敏值。
 *
 * @param kind 脱敏类别
 * @param value 最终日志中展示的值
 */
public record MaskedValue(String kind, String value) {
}
