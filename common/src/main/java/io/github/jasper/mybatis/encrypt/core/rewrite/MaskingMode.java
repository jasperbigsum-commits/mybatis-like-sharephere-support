package io.github.jasper.mybatis.encrypt.core.rewrite;

/**
 * SQL 改写日志中的参数脱敏模式。
 *
 * <p>`MASKED` 用于密文与 LIKE 查询值，日志中不展示真实内容；
 * `HASH` 用于辅助等值查询值，允许展示转换后的哈希结果以便排查命中问题。</p>
 */
enum MaskingMode {
    MASKED,
    HASH
}
