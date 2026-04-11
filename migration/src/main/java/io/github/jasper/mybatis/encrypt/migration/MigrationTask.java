package io.github.jasper.mybatis.encrypt.migration;

/**
 * Executable migration task.
 */
public interface MigrationTask {

    /**
     * Execute migration and return the latest cumulative progress.
     *
     * @return migration report
     */
    MigrationReport execute();
}
