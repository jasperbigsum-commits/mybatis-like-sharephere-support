package io.github.jasper.mybatis.encrypt.util;

import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
@Tag("parser")
class JSqlParserSupportTest {

    @Test
    void shouldParseSqlContainingRepeatedBlankLines() throws Exception {
        String sql = "\ufeffselect id\n\n\nfrom user_account\n\nwhere phone = ?";

        Statement statement = JSqlParserSupport.parseStatement(sql);

        assertInstanceOf(Select.class, statement);
    }

    @Test
    void shouldCollapseBlankLinesOutsideQuotedTextOnly() {
        String sql = "select 'a\n\nb' as payload\n\n\nfrom user_account";

        String normalized = JSqlParserSupport.normalizeSqlForParser(sql);

        assertTrue(normalized.contains("'a\n\nb'"));
        assertFalse(normalized.contains("payload\n\n\nfrom"));
    }
}
