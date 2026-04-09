package io.github.jasper.mybatis.encrypt.algorithm;

/**
 * 辅助等值查询算法 SPI。
 *
 * <p>该接口用于把业务明文转换为辅助查询列的值，典型场景是使用摘要算法为
 * 等值查询生成稳定值，从而避免在查询时对主密文列做解密匹配。</p>
 */
public interface AssistedQueryAlgorithm {

    /**
     * 将业务明文转换为可写入辅助查询列的值。
     *
     * @param plainText 业务侧明文
     * @return 辅助查询列值
     */
    String transform(String plainText);
}
