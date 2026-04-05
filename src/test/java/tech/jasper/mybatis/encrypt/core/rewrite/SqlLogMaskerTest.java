package tech.jasper.mybatis.encrypt.core.rewrite;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SqlLogMaskerTest {

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
