package io.github.jasper.mybatis.encrypt.util;

import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
@Tag("parser")
class JSqlParserSupportTest {

    /**
     * 测试目的：验证 SQL 解析工具在包含空行或引号文本时仍能稳定解析。
     * 测试场景：构造带重复空行、字符串字面量和普通 SQL 的输入，断言预处理不会破坏 SQL 语义。
     */
    @Test
    void shouldParseSqlContainingRepeatedBlankLines() throws Exception {
        String sql = "\ufeffselect id\n\n\nfrom user_account\n\nwhere phone = ?";

        Statement statement = JSqlParserSupport.parseStatement(sql);

        assertInstanceOf(Select.class, statement);
    }

    /**
     * 测试目的：验证 SQL 解析工具在包含空行或引号文本时仍能稳定解析。
     * 测试场景：构造带重复空行、字符串字面量和普通 SQL 的输入，断言预处理不会破坏 SQL 语义。
     */
    @Test
    void shouldCollapseBlankLinesOutsideQuotedTextOnly() {
        String sql = "select 'a\n\nb' as payload\n\n\nfrom user_account";

        String normalized = JSqlParserSupport.normalizeSqlForParser(sql);

        assertTrue(normalized.contains("'a\n\nb'"));
        assertFalse(normalized.contains("payload\n\n\nfrom"));
    }
}
