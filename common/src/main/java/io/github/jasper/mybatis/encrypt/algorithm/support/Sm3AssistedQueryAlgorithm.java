package io.github.jasper.mybatis.encrypt.algorithm.support;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import io.github.jasper.mybatis.encrypt.algorithm.AssistedQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.exception.EncryptionConfigurationException;
import org.bouncycastle.util.encoders.Hex;

/**
 * 基于 SM3 的国密辅助等值查询算法实现。
 *
 * <p>将明文做 SM3 摘要后输出为小写十六进制字符串。使用 ThreadLocal 复用摘要实例以兼顾线程安全和吞吐。
 * 依赖 BouncyCastle 提供 SM3 支持。</p>
 */
public class Sm3AssistedQueryAlgorithm implements AssistedQueryAlgorithm {

    // 摘要对象本身不是线程安全的，按线程复用可以兼顾安全性和吞吐。
    private static final ThreadLocal<MessageDigest> SM3_HOLDER = ThreadLocal.withInitial(() -> {
        BouncyCastleProviderHolder.ensureRegistered();
        try {
            return MessageDigest.getInstance("SM3", BouncyCastleProviderHolder.PROVIDER_NAME);
        } catch (GeneralSecurityException ex) {
            throw new EncryptionConfigurationException("Failed to initialize SM3 digest.", ex);
        }
    });

    private final byte[] saltBytes;


    /**
     * 不含有salt的sm3的方式
     */
    public Sm3AssistedQueryAlgorithm() {
        this.saltBytes = new byte[0];
    }

    /**
     * 含有salt的sm3的方式
     * @param salt 16进制固定盐
     */
    public Sm3AssistedQueryAlgorithm(String salt) {
        this.saltBytes = Hex.decode(salt);
    }

    @Override
    public String transform(String plainText) {
        if (plainText == null) {
            return null;
        }
        MessageDigest digest = SM3_HOLDER.get();
        // ThreadLocal 量会复用同一个摘要实例，因此每次使用前都必须显式 reset。
        digest.reset();
        byte[] plainTextBytes = plainText.getBytes(StandardCharsets.UTF_8);
        // salt + plainText
        byte[] mergeTextBytes = ByteBuffer.allocate(saltBytes.length + plainTextBytes.length)
                .put(saltBytes)
                .put(plainTextBytes)
                .array();
        byte[] hash = digest.digest(mergeTextBytes);
        StringBuilder builder = new StringBuilder(hash.length * 2);
        for (byte current : hash) {
            builder.append(String.format("%02x", current));
        }
        return builder.toString();
    }
}
