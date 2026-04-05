package tech.jasper.mybatis.encrypt.algorithm.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class SmAlgorithmsTest {

    @Test
    void shouldEncryptAndDecryptWithSm4() {
        Sm4CipherAlgorithm algorithm = new Sm4CipherAlgorithm("unit-test-key");

        String encrypted = algorithm.encrypt("13800138000");

        assertNotEquals("13800138000", encrypted);
        assertEquals("13800138000", algorithm.decrypt(encrypted));
    }

    @Test
    void shouldProduceStableSm3Digest() {
        Sm3AssistedQueryAlgorithm algorithm = new Sm3AssistedQueryAlgorithm();

        String digest = algorithm.transform("13800138000");

        assertEquals(digest, algorithm.transform("13800138000"));
    }
}
