package io.github.jasper.mybatis.encrypt.algorithm.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BusinessLikeQueryAlgorithmsTest {

    @Test
    void shouldMaskIdCardByKeepingFrontAndBackThreeChars() {
        IdCardMaskLikeQueryAlgorithm algorithm = new IdCardMaskLikeQueryAlgorithm();

        assertEquals("110************234", algorithm.transform("110101199001011234"));
        assertEquals("abc123", algorithm.transform("abc123"));
        assertNull(algorithm.transform(null));
    }

    @Test
    void shouldMaskPhoneAndLandlineByKeepingLastFourChars() {
        PhoneNumberMaskLikeQueryAlgorithm algorithm = new PhoneNumberMaskLikeQueryAlgorithm();

        assertEquals("*******8000", algorithm.transform("13800138000"));
        assertEquals("*******5678", algorithm.transform("01012345678"));
        assertEquals("1234", algorithm.transform("1234"));
    }

    @Test
    void shouldMaskBankCardByKeepingLastFourChars() {
        BankCardMaskLikeQueryAlgorithm algorithm = new BankCardMaskLikeQueryAlgorithm();

        assertEquals("***************0123", algorithm.transform("6222021234567890123"));
        assertEquals("0123", algorithm.transform("0123"));
    }

    @Test
    void shouldMaskPersonalNamesByCommonChineseRules() {
        NameMaskLikeQueryAlgorithm algorithm = new NameMaskLikeQueryAlgorithm();

        assertEquals("张*", algorithm.transform("张三"));
        assertEquals("王*明", algorithm.transform("王小明"));
        assertEquals("欧**娜", algorithm.transform("欧阳娜娜"));
    }

    @Test
    void shouldMaskOrganizationNamesByKeepingLocationPrefixHeadAndTail() {
        NameMaskLikeQueryAlgorithm algorithm = new NameMaskLikeQueryAlgorithm();

        assertEquals("北京市字节****公司", algorithm.transform("北京市字节跳动有限公司"));
        assertEquals("杭州未来**公司", algorithm.transform("杭州未来科技公司"));
    }
}
