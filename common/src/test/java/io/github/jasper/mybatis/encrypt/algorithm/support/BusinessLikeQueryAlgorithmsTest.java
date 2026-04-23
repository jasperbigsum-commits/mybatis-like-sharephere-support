package io.github.jasper.mybatis.encrypt.algorithm.support;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@Tag("unit")
@Tag("algorithm")
class BusinessLikeQueryAlgorithmsTest {

    /**
     * 测试目的：验证内置加密、哈希、LIKE 归一化和业务脱敏算法输出稳定。
     * 测试场景：输入手机号、身份证、银行卡、姓名等典型业务值，断言密文、摘要或脱敏格式符合规则。
     */
    @Test
    void shouldMaskIdCardByKeepingFrontAndBackThreeChars() {
        IdCardMaskLikeQueryAlgorithm algorithm = new IdCardMaskLikeQueryAlgorithm();

        assertEquals("110************234", algorithm.transform("110101199001011234"));
        assertEquals("abc123", algorithm.transform("abc123"));
        assertNull(algorithm.transform(null));
    }

    /**
     * 测试目的：验证内置加密、哈希、LIKE 归一化和业务脱敏算法输出稳定。
     * 测试场景：输入手机号、身份证、银行卡、姓名等典型业务值，断言密文、摘要或脱敏格式符合规则。
     */
    @Test
    void shouldMaskPhoneAndLandlineByKeepingLastFourChars() {
        PhoneNumberMaskLikeQueryAlgorithm algorithm = new PhoneNumberMaskLikeQueryAlgorithm();

        assertEquals("*******8000", algorithm.transform("13800138000"));
        assertEquals("*******5678", algorithm.transform("01012345678"));
        assertEquals("1234", algorithm.transform("1234"));
    }

    /**
     * 测试目的：验证内置加密、哈希、LIKE 归一化和业务脱敏算法输出稳定。
     * 测试场景：输入手机号、身份证、银行卡、姓名等典型业务值，断言密文、摘要或脱敏格式符合规则。
     */
    @Test
    void shouldMaskBankCardByKeepingLastFourChars() {
        BankCardMaskLikeQueryAlgorithm algorithm = new BankCardMaskLikeQueryAlgorithm();

        assertEquals("***************0123", algorithm.transform("6222021234567890123"));
        assertEquals("0123", algorithm.transform("0123"));
    }

    /**
     * 测试目的：验证内置加密、哈希、LIKE 归一化和业务脱敏算法输出稳定。
     * 测试场景：输入手机号、身份证、银行卡、姓名等典型业务值，断言密文、摘要或脱敏格式符合规则。
     */
    @Test
    void shouldMaskPersonalNamesByCommonChineseRules() {
        NameMaskLikeQueryAlgorithm algorithm = new NameMaskLikeQueryAlgorithm();

        assertEquals("张*", algorithm.transform("张三"));
        assertEquals("王*明", algorithm.transform("王小明"));
        assertEquals("欧**娜", algorithm.transform("欧阳娜娜"));
    }

    /**
     * 测试目的：验证内置加密、哈希、LIKE 归一化和业务脱敏算法输出稳定。
     * 测试场景：输入手机号、身份证、银行卡、姓名等典型业务值，断言密文、摘要或脱敏格式符合规则。
     */
    @Test
    void shouldMaskOrganizationNamesByKeepingLocationPrefixHeadAndTail() {
        NameMaskLikeQueryAlgorithm algorithm = new NameMaskLikeQueryAlgorithm();

        assertEquals("北京市字节****公司", algorithm.transform("北京市字节跳动有限公司"));
        assertEquals("杭州未来**公司", algorithm.transform("杭州未来科技公司"));
    }
}
