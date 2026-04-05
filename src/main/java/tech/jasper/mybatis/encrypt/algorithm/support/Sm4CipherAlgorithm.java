package tech.jasper.mybatis.encrypt.algorithm.support;

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
import tech.jasper.mybatis.encrypt.algorithm.CipherAlgorithm;
import tech.jasper.mybatis.encrypt.exception.EncryptionConfigurationException;

public class Sm4CipherAlgorithm implements CipherAlgorithm {

    private static final String TRANSFORMATION = "SM4/GCM/NoPadding";
    private static final int IV_SIZE = 12;
    private static final int TAG_SIZE_BITS = 128;
    private static final int KEY_SIZE = 16;

    private final SecretKeySpec keySpec;
    private final SecureRandom secureRandom = new SecureRandom();

    public Sm4CipherAlgorithm(String keyMaterial) {
        // Provider 只注册一次，避免每次创建算法实例都重复触发安全组件初始化。
        BouncyCastleProviderHolder.ensureRegistered();
        this.keySpec = new SecretKeySpec(deriveKey(keyMaterial), "SM4");
    }

    @Override
    public String encrypt(String plainText) {
        if (plainText == null) {
            return null;
        }
        try {
            // 每次加密使用独立随机 IV，避免同明文同密钥下产生相同密文。
            byte[] iv = new byte[IV_SIZE];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION, BouncyCastleProviderHolder.PROVIDER_NAME);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_SIZE_BITS, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            // 输出中同时携带 IV 和密文，方便解密端无状态还原。
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + encrypted.length)
                    .put(iv)
                    .put(encrypted)
                    .array());
        } catch (GeneralSecurityException ex) {
            throw new EncryptionConfigurationException("Failed to encrypt value with SM4.", ex);
        }
    }

    @Override
    public String decrypt(String cipherText) {
        if (cipherText == null) {
            return null;
        }
        try {
            // 编码载荷布局固定为 [IV][cipherText]，便于跨实例解密。
            byte[] payload = Base64.getDecoder().decode(cipherText);
            byte[] iv = Arrays.copyOfRange(payload, 0, IV_SIZE);
            byte[] encrypted = Arrays.copyOfRange(payload, IV_SIZE, payload.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION, BouncyCastleProviderHolder.PROVIDER_NAME);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_SIZE_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new EncryptionConfigurationException("Failed to decrypt value with SM4.", ex);
        }
    }

    private byte[] deriveKey(String keyMaterial) {
        if (keyMaterial == null || keyMaterial.isBlank()) {
            throw new EncryptionConfigurationException("mybatis.encrypt.default-cipher-key must not be blank.");
        }
        try {
            // 对任意输入密钥材料做一次稳定摘要，得到固定长度的 SM4 密钥。
            MessageDigest digest = MessageDigest.getInstance("SM3", BouncyCastleProviderHolder.PROVIDER_NAME);
            return Arrays.copyOf(digest.digest(keyMaterial.getBytes(StandardCharsets.UTF_8)), KEY_SIZE);
        } catch (GeneralSecurityException ex) {
            throw new EncryptionConfigurationException("Failed to initialize SM4 key.", ex);
        }
    }
}
