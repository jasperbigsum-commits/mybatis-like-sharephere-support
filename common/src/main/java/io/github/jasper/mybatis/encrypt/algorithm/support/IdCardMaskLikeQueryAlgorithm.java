package io.github.jasper.mybatis.encrypt.algorithm.support;

import io.github.jasper.mybatis.encrypt.algorithm.LikeQueryAlgorithm;

/**
 * 身份证号脱敏 LIKE 算法。
 *
 * <p>默认保留前后各 3 位，中间使用 `*` 覆盖。</p>
 */
public final class IdCardMaskLikeQueryAlgorithm implements LikeQueryAlgorithm {

    private final KeepFirstNLastMLikeQueryAlgorithm delegate = new KeepFirstNLastMLikeQueryAlgorithm(3, 3);

    @Override
    public String transform(String plainText) {
        return delegate.transform(plainText);
    }
}
