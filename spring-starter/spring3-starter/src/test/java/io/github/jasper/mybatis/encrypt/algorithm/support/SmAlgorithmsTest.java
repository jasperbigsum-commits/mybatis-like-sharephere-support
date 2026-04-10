package io.github.jasper.mybatis.encrypt.algorithm.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class SmAlgorithmsTest {

    @Test
    void shouldEncryptAndDecryptWithSm4() {
        Sm4CipherAlgorithm algorithm = new Sm4CipherAlgorithm("rcb@Xk9mPq2L");

        String encrypted = algorithm.encrypt("13800138000");

        System.out.printf("明文：%s, 密文：%s%n", "13800138000", encrypted);

        assertNotEquals("13800138000", encrypted);
        assertEquals("13800138000", algorithm.decrypt(encrypted));
    }

    @Test
    void shouldProduceStableSm3Digest() {

        Sm3AssistedQueryAlgorithm algorithm = new Sm3AssistedQueryAlgorithm("a3f5c2e8d1b4a7c9e6f0d3b8c5a2e7d9");

        String digest = algorithm.transform("13800138000");

        System.out.printf("明文：%s, 密文：%s%n", "13800138000", digest);

        assertEquals(digest, algorithm.transform("13800138000"));
    }
}
