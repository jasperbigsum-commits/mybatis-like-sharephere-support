package io.github.jasper.mybatis.encrypt.core.rewrite;

import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import net.sf.jsqlparser.schema.Column;

/**
 * 记录一次 SQL 列解析结果。
 *
 * <p>把“当前表达式命中了哪个加密列规则”抽成独立值对象，
 * 供条件改写、投影改写等多个协作对象复用。</p>
 */
final class ColumnResolution {

    private final Column column;
    private final EncryptColumnRule rule;
    private final boolean leftColumn;

    ColumnResolution(Column column, EncryptColumnRule rule, boolean leftColumn) {
        this.column = column;
        this.rule = rule;
        this.leftColumn = leftColumn;
    }

    Column column() {
        return column;
    }

    EncryptColumnRule rule() {
        return rule;
    }

    boolean leftColumn() {
        return leftColumn;
    }
}
