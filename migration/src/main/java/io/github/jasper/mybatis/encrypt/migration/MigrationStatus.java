package io.github.jasper.mybatis.encrypt.migration;

/**
 * Task state persisted for resuming and audit.
 */
public enum MigrationStatus {
    READY,
    RUNNING,
    FAILED,
    COMPLETED
}
