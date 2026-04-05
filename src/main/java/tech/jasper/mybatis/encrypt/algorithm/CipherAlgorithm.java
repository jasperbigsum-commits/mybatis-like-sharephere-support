package tech.jasper.mybatis.encrypt.algorithm;

/**
 * 主加密算法 SPI。
 *
 * <p>该接口负责字段主列的加密写入与读取后的解密，是数据库密文存储的核心扩展点。
 * 默认实现要求具备双向可逆能力，因此不适用于单向哈希类算法。</p>
 */
public interface CipherAlgorithm {

    /**
     * 将业务明文转换为数据库主列可存储的密文。
     *
     * @param plainText 业务侧明文
     * @return 持久化到主加密列的密文
     */
    String encrypt(String plainText);

    /**
     * 将数据库主列中的密文恢复为业务可读明文。
     *
     * @param cipherText 数据库中的密文
     * @return 解密后的业务明文
     */
    String decrypt(String cipherText);
}
