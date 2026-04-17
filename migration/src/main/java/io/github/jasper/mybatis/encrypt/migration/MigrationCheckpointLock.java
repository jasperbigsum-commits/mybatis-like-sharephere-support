package io.github.jasper.mybatis.encrypt.migration;

/**
 * Exclusive execution lock for one migration checkpoint scope.
 */
public interface MigrationCheckpointLock extends AutoCloseable {

    /**
     * Release the current checkpoint lock.
     */
    @Override
    void close();
}
