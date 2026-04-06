package tech.jasper.mybatis.encrypt.algorithm.support;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import tech.jasper.mybatis.encrypt.algorithm.AssistedQueryAlgorithm;
import tech.jasper.mybatis.encrypt.exception.EncryptionConfigurationException;

/**
 * 基于 SHA-256 的辅助等值查询算法实现。
 *
 * <p>将明文做 SHA-256 摘要后输出为小写十六进制字符串，用于辅助查询列的等值匹配。</p>
 */
public class Sha256AssistedQueryAlgorithm implements AssistedQueryAlgorithm {

    @Override
    public String transform(String plainText) {
        if (plainText == null) {
            return null;
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(plainText.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte current : hash) {
                builder.append(String.format("%02x", current));
            }
            return builder.toString();
        } catch (GeneralSecurityException ex) {
            throw new EncryptionConfigurationException("Failed to hash assisted query value.", ex);
        }
    }
}
