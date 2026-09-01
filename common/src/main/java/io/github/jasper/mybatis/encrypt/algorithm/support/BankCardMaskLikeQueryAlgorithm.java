package io.github.jasper.mybatis.encrypt.algorithm.support;

import io.github.jasper.mybatis.encrypt.algorithm.LikeQueryAlgorithm;

/**
 * 银行卡号脱敏 LIKE 算法。
 *
 * <p>默认仅保留后 4 位，其余字符使用 `*` 覆盖。</p>
 */
public final class BankCardMaskLikeQueryAlgorithm implements LikeQueryAlgorithm {

    private final KeepFirstNLastMLikeQueryAlgorithm delegate = new KeepFirstNLastMLikeQueryAlgorithm(0, 4);

    @Override
    public String transform(String plainText) {
        return delegate.transform(plainText);
    }
}
