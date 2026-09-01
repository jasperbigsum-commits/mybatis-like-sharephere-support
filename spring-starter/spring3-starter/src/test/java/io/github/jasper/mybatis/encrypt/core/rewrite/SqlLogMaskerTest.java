package io.github.jasper.mybatis.encrypt.core.rewrite;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
@Tag("rewrite")
class SqlLogMaskerTest {

    /**
     * 测试目的：验证 SQL 改写核心组件在当前语句结构下保持安全且确定的改写行为。
     * 测试场景：构造对应 SQL、加密规则和参数上下文，断言 AST 改写结果、参数绑定和安全边界。
     */
    @Test
    void shouldMaskCipherAndPreserveHashValues() {
        SqlLogMasker masker = new SqlLogMasker();
        Map<String, MaskedValue> params = new LinkedHashMap<>();
        params.put("__encrypt_generated_1", new MaskedValue("MASKED", "***"));
        params.put("__encrypt_generated_2", new MaskedValue("HASH", "abc123hash"));

        String masked = masker.mask("select * from user_account where phone_hash = ?", params);

        assertTrue(masked.contains("__encrypt_generated_1=***"));
        assertTrue(masked.contains("__encrypt_generated_2=abc123hash"));
        assertTrue(masked.contains("phone_hash = ?"));
    }
}
