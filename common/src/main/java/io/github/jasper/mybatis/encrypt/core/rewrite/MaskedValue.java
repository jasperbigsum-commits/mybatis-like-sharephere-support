package io.github.jasper.mybatis.encrypt.core.rewrite;

/**
 * 日志脱敏值。
 */
public final class MaskedValue {

    private final String kind;
    private final String value;

    /**
     * 日志脱敏值 构造方法
     * @param kind 脱敏类别
     * @param value 最终日志中展示的值
     */
    public MaskedValue(String kind, String value) {
        this.kind = kind;
        this.value = value;
    }

    /**
     * 获取脱敏类别
     * @return 脱敏类别
     */
    public String kind() {
        return kind;
    }

    /**
     * 获取最终日志中展示的值
     * @return 最终日志中展示的值
     */
    public String value() {
        return value;
    }
}
