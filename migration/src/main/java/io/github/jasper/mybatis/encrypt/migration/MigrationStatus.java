package io.github.jasper.mybatis.encrypt.migration;

/**
 * Task state persisted for resuming and audit.
 */
public enum MigrationStatus {
    /**
     * 待运行
     */
    READY,
    /**
     * 运行中
     */
    RUNNING,
    /**
     * 失败
     */
    FAILED,
    /**
     * 成功
     */
    COMPLETED
}
