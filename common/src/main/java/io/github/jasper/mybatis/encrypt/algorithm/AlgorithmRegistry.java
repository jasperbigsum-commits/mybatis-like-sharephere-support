package io.github.jasper.mybatis.encrypt.algorithm;

import java.util.Map;
import io.github.jasper.mybatis.encrypt.exception.EncryptionConfigurationException;

/**
 * 算法注册中心。
 *
 * <p>该组件把 Spring 容器中的算法实现按名称收拢为统一访问入口，避免 SQL 改写、
 * 结果解密等核心逻辑直接依赖 Bean 查找细节。</p>
 */
public class AlgorithmRegistry {

    private final Map<String, CipherAlgorithm> cipherAlgorithms;
    private final Map<String, AssistedQueryAlgorithm> assistedAlgorithms;
    private final Map<String, LikeQueryAlgorithm> likeAlgorithms;

    /**
     * 创建算法注册中心。
     *
     * @param cipherAlgorithms 密文算法映射
     * @param assistedAlgorithms 辅助查询算法映射
     * @param likeAlgorithms LIKE 查询算法映射
     */
    public AlgorithmRegistry(Map<String, CipherAlgorithm> cipherAlgorithms,
                             Map<String, AssistedQueryAlgorithm> assistedAlgorithms,
                             Map<String, LikeQueryAlgorithm> likeAlgorithms) {
        this.cipherAlgorithms = cipherAlgorithms;
        this.assistedAlgorithms = assistedAlgorithms;
        this.likeAlgorithms = likeAlgorithms;
    }

    /**
     * 按名称获取密文算法。
     *
     * @param name 算法名称
     * @return 密文算法实现
     */
    public CipherAlgorithm cipher(String name) {
        CipherAlgorithm algorithm = cipherAlgorithms.get(name);
        if (algorithm == null) {
            throw new EncryptionConfigurationException("Missing cipher algorithm bean: " + name);
        }
        return algorithm;
    }

    /**
     * 按名称获取辅助查询算法。
     *
     * @param name 算法名称
     * @return 辅助查询算法实现
     */
    public AssistedQueryAlgorithm assisted(String name) {
        AssistedQueryAlgorithm algorithm = assistedAlgorithms.get(name);
        if (algorithm == null) {
            throw new EncryptionConfigurationException("Missing assisted query algorithm bean: " + name);
        }
        return algorithm;
    }

    /**
     * 按名称获取 LIKE 查询算法。
     *
     * @param name 算法名称
     * @return LIKE 查询算法实现
     */
    public LikeQueryAlgorithm like(String name) {
        LikeQueryAlgorithm algorithm = likeAlgorithms.get(name);
        if (algorithm == null) {
            throw new EncryptionConfigurationException("Missing like query algorithm bean: " + name);
        }
        return algorithm;
    }
}
