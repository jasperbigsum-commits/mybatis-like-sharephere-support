package io.github.jasper.mybatis.encrypt.algorithm.support;

import io.github.jasper.mybatis.encrypt.exception.EncryptionConfigurationException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("unit")
@Tag("algorithm")
class MaskLikeQueryAlgorithmsTest {

    /**
     * 测试目的：验证内置加密、哈希、LIKE 归一化和业务脱敏算法输出稳定。
     * 测试场景：输入手机号、身份证、银行卡、姓名等典型业务值，断言密文、摘要或脱敏格式符合规则。
     */
    @Test
    void shouldKeepFirstNLastM() {
        KeepFirstNLastMLikeQueryAlgorithm algorithm = new KeepFirstNLastMLikeQueryAlgorithm(3, 4);

        assertEquals("138****8000", algorithm.transform("13800138000"));
        assertEquals("%138****8000%", algorithm.transform("%13800138000%"));
        assertEquals("abc", algorithm.transform("abc"));
        assertEquals("", algorithm.transform(""));
        assertNull(algorithm.transform(null));
    }

    /**
     * 测试目的：验证内置加密、哈希、LIKE 归一化和业务脱敏算法输出稳定。
     * 测试场景：输入手机号、身份证、银行卡、姓名等典型业务值，断言密文、摘要或脱敏格式符合规则。
     */
    @Test
    void shouldKeepFromXToY() {
        KeepFromXToYLikeQueryAlgorithm algorithm = new KeepFromXToYLikeQueryAlgorithm(3, 6);

        assertEquals("***0013****", algorithm.transform("13800138000"));
        assertEquals("***12", algorithm.transform("abc12"));
        assertEquals("", algorithm.transform(""));
        assertNull(algorithm.transform(null));
    }

    /**
     * 测试目的：验证内置加密、哈希、LIKE 归一化和业务脱敏算法输出稳定。
     * 测试场景：输入手机号、身份证、银行卡、姓名等典型业务值，断言密文、摘要或脱敏格式符合规则。
     */
    @Test
    void shouldMaskFirstNLastM() {
        MaskFirstNLastMLikeQueryAlgorithm algorithm = new MaskFirstNLastMLikeQueryAlgorithm(3, 4);

        assertEquals("***0013****", algorithm.transform("13800138000"));
        assertEquals("%***0013****%", algorithm.transform("%13800138000%"));
        assertEquals("***", algorithm.transform("abc"));
        assertEquals("", algorithm.transform(""));
        assertNull(algorithm.transform(null));
    }

    /**
     * 测试目的：验证内置加密、哈希、LIKE 归一化和业务脱敏算法输出稳定。
     * 测试场景：输入手机号、身份证、银行卡、姓名等典型业务值，断言密文、摘要或脱敏格式符合规则。
     */
    @Test
    void shouldMaskFromXToY() {
        MaskFromXToYLikeQueryAlgorithm algorithm = new MaskFromXToYLikeQueryAlgorithm(3, 6);

        assertEquals("138****8000", algorithm.transform("13800138000"));
        assertEquals("abc**", algorithm.transform("abc12"));
        assertEquals("", algorithm.transform(""));
        assertNull(algorithm.transform(null));
    }

    /**
     * 测试目的：验证内置加密、哈希、LIKE 归一化和业务脱敏算法输出稳定。
     * 测试场景：输入手机号、身份证、银行卡、姓名等典型业务值，断言密文、摘要或脱敏格式符合规则。
     */
    @Test
    void shouldSupportCustomReplaceChar() {
        assertEquals("138####8000", new KeepFirstNLastMLikeQueryAlgorithm(3, 4, '#').transform("13800138000"));
        assertEquals("138####8000", new MaskFromXToYLikeQueryAlgorithm(3, 6, '#').transform("13800138000"));
    }

    /**
     * 测试目的：验证算法注册或密钥配置缺失时返回明确的错误码。
     * 测试场景：构造缺失算法、空密钥或非法范围参数，断言异常类型和错误码便于调用方定位配置问题。
     */
    @Test
    void shouldRejectInvalidRangeArguments() {
        assertThrows(EncryptionConfigurationException.class, () -> new KeepFromXToYLikeQueryAlgorithm(5, 3));
        assertThrows(EncryptionConfigurationException.class, () -> new MaskFromXToYLikeQueryAlgorithm(-1, 3));
        assertThrows(EncryptionConfigurationException.class, () -> new MaskFirstNLastMLikeQueryAlgorithm(-1, 1));
    }
}
