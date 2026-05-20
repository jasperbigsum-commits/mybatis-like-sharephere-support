package io.github.jasper.mybatis.encrypt.jdbc;

import java.util.List;
import java.util.Map;

/**
 * Spring-managed JDBC facade that applies encrypted SQL rewrite and result decryption.
 */
public interface EncryptedJdbcExecutor {

    /**
     * Executes a select statement against the named encrypted datasource.
     *
     * @param dataSourceName encryption configuration datasource name
     * @param sql raw SQL
     * @param args positional bind arguments
     * @return query rows
     */
    List<Map<String, Object>> select(String dataSourceName, String sql, Object... args);

    /**
     * Executes an insert statement against the named encrypted datasource.
     *
     * @param dataSourceName encryption configuration datasource name
     * @param sql raw SQL
     * @param args positional bind arguments
     * @return affected rows
     */
    int insert(String dataSourceName, String sql, Object... args);

    /**
     * Executes an update statement against the named encrypted datasource.
     *
     * @param dataSourceName encryption configuration datasource name
     * @param sql raw SQL
     * @param args positional bind arguments
     * @return affected rows
     */
    int update(String dataSourceName, String sql, Object... args);

    /**
     * Executes a delete statement against the named encrypted datasource.
     *
     * @param dataSourceName encryption configuration datasource name
     * @param sql raw SQL
     * @param args positional bind arguments
     * @return affected rows
     */
    int delete(String dataSourceName, String sql, Object... args);
}
