package tech.jasper.mybatis.encrypt.algorithm.support;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import tech.jasper.mybatis.encrypt.algorithm.AssistedQueryAlgorithm;
import tech.jasper.mybatis.encrypt.exception.EncryptionConfigurationException;

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

    @Override
    public String transform(String plainText) {
        if (plainText == null) {
            return null;
        }
        MessageDigest digest = SM3_HOLDER.get();
        // ThreadLocal 会复用同一个摘要实例，因此每次使用前必须显式 reset。
        digest.reset();
        byte[] hash = digest.digest(plainText.getBytes(StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder(hash.length * 2);
        for (byte current : hash) {
            builder.append(String.format("%02x", current));
        }
        return builder.toString();
    }
}
