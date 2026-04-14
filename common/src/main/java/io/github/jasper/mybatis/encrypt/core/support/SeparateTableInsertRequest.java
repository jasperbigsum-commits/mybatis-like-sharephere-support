package io.github.jasper.mybatis.encrypt.core.support;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 独立加密表插入请求。
 *
 * <p>使用有序 Map 保存列和值，既方便 JDBC 回退直接绑定，也方便走 MyBatis
 * 动态构造的 MappedStatement 时保持参数顺序稳定。</p>
 */
public class SeparateTableInsertRequest {

    private final String table;
    private final LinkedHashMap<String, Object> columnValues;

    /**
     * 创建独立表插入请求。
     *
     * @param table 目标物理表名
     * @param columnValues 按插入顺序排列的列值映射
     */
    public SeparateTableInsertRequest(String table, Map<String, Object> columnValues) {
        this.table = table;
        this.columnValues = new LinkedHashMap<>(columnValues);
    }

    /**
     * 返回目标物理表名。
     *
     * @return 目标物理表名
     */
    public String getTable() {
        return table;
    }

    /**
     * 返回按插入顺序排列的列值映射。
     *
     * @return 不可变列值映射
     */
    public Map<String, Object> getColumnValues() {
        return Collections.unmodifiableMap(columnValues);
    }
}
