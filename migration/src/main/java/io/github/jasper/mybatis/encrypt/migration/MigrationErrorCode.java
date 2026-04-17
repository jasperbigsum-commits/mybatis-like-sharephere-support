package io.github.jasper.mybatis.encrypt.migration;

/**
 * Structured error codes for migration planning, execution and resume flows.
 */
public enum MigrationErrorCode {
    /**
     * 通用错误
     */
    GENERAL_FAILURE,
    /**
     * 定义无效
     */
    DEFINITION_INVALID,
    /**
     * 元信息规则缺失
     */
    METADATA_RULE_MISSING,
    /**
     * 字段选择器未解决
     */
    FIELD_SELECTOR_UNRESOLVED,
    /**
     * 备份字段冲突
     */
    BACKUP_COLUMN_CONFLICT,
    /**
     * 游标列会被迁移过程改写
     */
    CURSOR_COLUMN_MUTABLE,
    /**
     * 迁移目标表被全局排除
     */
    TABLE_EXCLUDED,
    /**
     * 游标值缺失
     */
    CURSOR_VALUE_MISSING,
    /**
     * checkpoint 锁已被占用
     */
    CHECKPOINT_LOCKED,
    /**
     * 游标检查点无效
     */
    CURSOR_CHECKPOINT_INVALID,
    /**
     * 范围内读取失败
     */
    RANGE_READ_FAILED,
    /**
     * 执行失败
     */
    EXECUTION_FAILED,
    /**
     * 需确认信息
     */
    CONFIRMATION_REQUIRED,
    /**
     * 确认目标未匹配
     */
    CONFIRMATION_SCOPE_MISMATCH,
    /**
     * 确认时IO错误
     */
    CONFIRMATION_IO_FAILED,
    /**
     * 状态存储IO错误
     */
    STATE_STORE_IO_FAILED,
    /**
     * 状态存储数据非法
     */
    STATE_STORE_DATA_INVALID,
    /**
     * 验证环节引用信息缺失
     */
    VERIFICATION_REFERENCE_MISSING,
    /**
     * 验证环节主行记录缺失
     */
    VERIFICATION_MAIN_ROW_MISSING,
    /**
     * 验证环节扩展行记录缺失
     */
    VERIFICATION_EXTERNAL_ROW_MISSING,
    /**
     * 验证环节值未匹配
     */
    VERIFICATION_VALUE_MISMATCH
}
