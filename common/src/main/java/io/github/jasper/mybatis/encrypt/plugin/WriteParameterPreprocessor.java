package io.github.jasper.mybatis.encrypt.plugin;

import org.apache.ibatis.mapping.MappedStatement;

/**
 * Write-time parameter preprocessor.
 *
 * <p>Implementations may mutate the runtime parameter object in place before MyBatis builds
 * {@code BoundSql} for {@code INSERT}/{@code UPDATE} statements. The hook is intended for
 * framework integrations that derive audit fields, tenant values, or other write-only metadata
 * from the current execution context.</p>
 */
public interface WriteParameterPreprocessor {

    /**
     * Preprocesses the runtime parameter object for the current write statement.
     *
     * @param mappedStatement current mapped statement
     * @param parameterObject runtime parameter object passed to MyBatis
     */
    void preprocess(MappedStatement mappedStatement, Object parameterObject);
}
