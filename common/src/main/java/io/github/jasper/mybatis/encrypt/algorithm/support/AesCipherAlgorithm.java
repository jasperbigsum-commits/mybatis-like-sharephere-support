package io.github.jasper.mybatis.encrypt.algorithm.support;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import io.github.jasper.mybatis.encrypt.algorithm.CipherAlgorithm;
import io.github.jasper.mybatis.encrypt.exception.EncryptionConfigurationException;
import io.github.jasper.mybatis.encrypt.exception.EncryptionErrorCode;
import io.github.jasper.mybatis.encrypt.util.StringUtils;

/**
 * 基于 AES-256-GCM 的主加密算法实现。
 *
 * <p>每次加密使用独立随机 IV，输出格式为 Base64([IV][ciphertext+tag])。
 * 密钥由任意字符串经 SHA-256 摘要后截取前 16 字节派生。</p>
 */
public class AesCipherAlgorithm implements CipherAlgorithm {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_SIZE = 12;
    private static final int TAG_SIZE_BITS = 128;

    private final SecretKeySpec keySpec;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 使用给定密钥材料创建 AES 算法实例。
     *
     * @param keyMaterial 原始密钥材料
     */
    public AesCipherAlgorithm(String keyMaterial) {
        this.keySpec = new SecretKeySpec(deriveKey(keyMaterial), "AES");
    }

    @Override
    public String encrypt(String plainText) {
        if (plainText == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_SIZE];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_SIZE_BITS, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + encrypted.length)
                    .put(iv)
                    .put(encrypted)
                    .array());
        } catch (GeneralSecurityException ex) {
            throw new EncryptionConfigurationException(EncryptionErrorCode.CIPHER_OPERATION_FAILED,
                    "Failed to encrypt value.", ex);
        }
    }

    @Override
    public String decrypt(String cipherText) {
        if (cipherText == null) {
            return null;
        }
        try {
            byte[] payload = Base64.getDecoder().decode(cipherText);
            byte[] iv = Arrays.copyOfRange(payload, 0, IV_SIZE);
            byte[] encrypted = Arrays.copyOfRange(payload, IV_SIZE, payload.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_SIZE_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new EncryptionConfigurationException(EncryptionErrorCode.CIPHER_OPERATION_FAILED,
                    "Failed to decrypt value.", ex);
        }
    }

    private byte[] deriveKey(String keyMaterial) {
        if (StringUtils.isBlank(keyMaterial)) {
            throw new EncryptionConfigurationException(EncryptionErrorCode.INVALID_FIELD_RULE,
                    "mybatis.encrypt.default-cipher-key must not be blank.");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Arrays.copyOf(digest.digest(keyMaterial.getBytes(StandardCharsets.UTF_8)), 16);
        } catch (GeneralSecurityException ex) {
            throw new EncryptionConfigurationException(EncryptionErrorCode.CIPHER_OPERATION_FAILED,
                    "Failed to initialize AES key.", ex);
        }
    }
}
