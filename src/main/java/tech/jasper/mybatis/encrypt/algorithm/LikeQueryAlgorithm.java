package tech.jasper.mybatis.encrypt.algorithm;

/**
 * LIKE 查询辅助算法 SPI。
 *
 * <p>该接口用于生成独立的 LIKE 查询辅助列值。它并不负责主密文字段的加解密，
 * 也不保证通用数据库上的任意模糊匹配语义，只负责生成一份可用于特定查询策略的
 * 预处理结果。</p>
 */
public interface LikeQueryAlgorithm {

    /**
     * 将业务明文转换为 LIKE 查询辅助列值。
     *
     * @param plainText 业务侧明文
     * @return LIKE 查询辅助值
     */
    String transform(String plainText);
}
