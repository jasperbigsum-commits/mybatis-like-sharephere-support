package io.github.jasper.mybatis.encrypt.exception;

/**
 * Structured runtime error codes for encryption configuration and SQL processing.
 */
public enum EncryptionErrorCode {

    /**
     * 通用失败。
     */
    GENERAL_FAILURE,
    /**
     * 缺少密文算法定义。
     */
    MISSING_CIPHER_ALGORITHM,
    /**
     * 缺少辅助等值查询算法定义。
     */
    MISSING_ASSISTED_QUERY_ALGORITHM,
    /**
     * 缺少 LIKE 查询算法定义。
     */
    MISSING_LIKE_QUERY_ALGORITHM,
    /**
     * 缺少响应字段脱敏器定义。
     */
    MISSING_SENSITIVE_FIELD_MASKER,
    /**
     * 表规则配置非法。
     */
    INVALID_TABLE_RULE,
    /**
     * 字段规则配置非法。
     */
    INVALID_FIELD_RULE,
    /**
     * 共享派生列配置了不一致的算法。
     */
    SHARED_DERIVED_COLUMN_ALGORITHM_MISMATCH,
    /**
     * 独立表模式缺少存储表定义。
     */
    MISSING_STORAGE_TABLE,
    /**
     * 缺少辅助等值查询列定义。
     */
    MISSING_ASSISTED_QUERY_COLUMN,
    /**
     * 缺少 LIKE 查询列定义。
     */
    MISSING_LIKE_QUERY_COLUMN,
    /**
     * 密文算法执行失败。
     */
    CIPHER_OPERATION_FAILED,
    /**
     * 辅助查询算法执行失败。
     */
    ASSISTED_QUERY_OPERATION_FAILED,
    /**
     * 独立表读写失败。
     */
    SEPARATE_TABLE_OPERATION_FAILED,
    /**
     * SQL 改写失败。
     */
    SQL_REWRITE_FAILED,
    /**
     * 加密字段引用存在歧义。
     */
    AMBIGUOUS_ENCRYPTED_REFERENCE,
    /**
     * 加密查询操作数非法。
     */
    INVALID_ENCRYPTED_QUERY_OPERAND,
    /**
     * 加密写入操作数非法。
     */
    INVALID_ENCRYPTED_WRITE_OPERAND,
    /**
     * 不支持的加密 INSERT。
     */
    UNSUPPORTED_ENCRYPTED_INSERT,
    /**
     * 不支持的加密 SELECT。
     */
    UNSUPPORTED_ENCRYPTED_SELECT,
    /**
     * 不支持的加密 IN 子查询。
     */
    UNSUPPORTED_ENCRYPTED_IN_QUERY,
    /**
     * 不支持的加密范围查询。
     */
    UNSUPPORTED_ENCRYPTED_RANGE,
    /**
     * 不支持的加密排序。
     */
    UNSUPPORTED_ENCRYPTED_ORDER_BY,
    /**
     * 不支持的加密 DISTINCT。
     */
    UNSUPPORTED_ENCRYPTED_DISTINCT,
    /**
     * 不支持的加密聚合。
     */
    UNSUPPORTED_ENCRYPTED_AGGREGATION,
    /**
     * 不支持的加密窗口函数。
     */
    UNSUPPORTED_ENCRYPTED_WINDOW,
    /**
     * 不支持的加密 GROUP BY。
     */
    UNSUPPORTED_ENCRYPTED_GROUP_BY,
    /**
     * 不支持的通用加密操作。
     */
    UNSUPPORTED_ENCRYPTED_OPERATION
}
