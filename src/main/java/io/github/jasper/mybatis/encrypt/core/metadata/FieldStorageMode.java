package io.github.jasper.mybatis.encrypt.core.metadata;

/**
 * 加密字段存储模式。
 */
public enum FieldStorageMode {

    /**
     * 加密字段与业务表存储在同一张表中。
     */
    SAME_TABLE,

    /**
     * 加密字段落到独立加密表中，业务表只保留主键等业务字段。
     */
    SEPARATE_TABLE
}
