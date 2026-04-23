package io.github.jasper.mybatis.encrypt.algorithm;

import io.github.jasper.mybatis.encrypt.exception.EncryptionConfigurationException;
import io.github.jasper.mybatis.encrypt.exception.EncryptionErrorCode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("unit")
@Tag("algorithm")
class AlgorithmRegistryTest {

    /**
     * 测试目的：验证算法注册或密钥配置缺失时返回明确的错误码。
     * 测试场景：构造缺失算法、空密钥或非法范围参数，断言异常类型和错误码便于调用方定位配置问题。
     */
    @Test
    void shouldExposeMissingCipherAlgorithmErrorCode() {
        AlgorithmRegistry registry = new AlgorithmRegistry(Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyMap());

        EncryptionConfigurationException exception = assertThrows(EncryptionConfigurationException.class,
                () -> registry.cipher("sm4"));

        assertEquals(EncryptionErrorCode.MISSING_CIPHER_ALGORITHM, exception.getErrorCode());
        assertEquals("Missing cipher algorithm bean: sm4", exception.getMessage());
    }

    /**
     * 测试目的：验证算法注册或密钥配置缺失时返回明确的错误码。
     * 测试场景：构造缺失算法、空密钥或非法范围参数，断言异常类型和错误码便于调用方定位配置问题。
     */
    @Test
    void shouldExposeMissingAssistedAlgorithmErrorCode() {
        AlgorithmRegistry registry = new AlgorithmRegistry(Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyMap());

        EncryptionConfigurationException exception = assertThrows(EncryptionConfigurationException.class,
                () -> registry.assisted("sm3"));

        assertEquals(EncryptionErrorCode.MISSING_ASSISTED_QUERY_ALGORITHM, exception.getErrorCode());
        assertEquals("Missing assisted query algorithm bean: sm3", exception.getMessage());
    }

    /**
     * 测试目的：验证算法注册或密钥配置缺失时返回明确的错误码。
     * 测试场景：构造缺失算法、空密钥或非法范围参数，断言异常类型和错误码便于调用方定位配置问题。
     */
    @Test
    void shouldExposeMissingLikeAlgorithmErrorCode() {
        AlgorithmRegistry registry = new AlgorithmRegistry(Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyMap());

        EncryptionConfigurationException exception = assertThrows(EncryptionConfigurationException.class,
                () -> registry.like("normalizedLike"));

        assertEquals(EncryptionErrorCode.MISSING_LIKE_QUERY_ALGORITHM, exception.getErrorCode());
        assertEquals("Missing like query algorithm bean: normalizedLike", exception.getMessage());
    }
}
