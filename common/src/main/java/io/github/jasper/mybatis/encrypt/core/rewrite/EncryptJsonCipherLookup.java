package io.github.jasper.mybatis.encrypt.core.rewrite;

import io.github.jasper.mybatis.encrypt.core.metadata.EncryptJsonPathRule;

/**
 * 按 path 规则和当前 hash 值回查密文。
 */
public interface EncryptJsonCipherLookup {

    /**
     * 按当前 path 规则与 hash 值查找密文。
     *
     * @param pathRule 当前 path 规则
     * @param currentHashValue 当前 JSON 中的 hash 值
     * @return 对应密文；未命中时返回 {@code null}
     */
    String findCipher(EncryptJsonPathRule pathRule, String currentHashValue);
}
