package io.github.jasper.mybatis.encrypt.algorithm.support;

/**
 * 覆盖前 N 位和后 M 位，中间保持原值。
 *
 * <p>语义参考 Apache ShardingSphere `MASK_FIRST_N_LAST_M`。</p>
 */
public final class MaskFirstNLastMLikeQueryAlgorithm extends AbstractMaskLikeQueryAlgorithm {

    private final int firstN;
    private final int lastM;
    private final char replaceChar;

    public MaskFirstNLastMLikeQueryAlgorithm(int firstN, int lastM) {
        this(firstN, lastM, DEFAULT_REPLACE_CHAR);
    }

    public MaskFirstNLastMLikeQueryAlgorithm(int firstN, int lastM, char replaceChar) {
        requireNonNegative("firstN", firstN);
        requireNonNegative("lastM", lastM);
        this.firstN = firstN;
        this.lastM = lastM;
        this.replaceChar = replaceChar;
    }

    @Override
    public String transform(String plainText) {
        if (null == plainText || plainText.isEmpty()) {
            return plainText;
        }
        char[] chars = plainText.toCharArray();
        for (int index = 0, length = Math.min(firstN, chars.length); index < length; index++) {
            chars[index] = replaceChar;
        }
        for (int index = chars.length - Math.min(lastM, chars.length); index < chars.length; index++) {
            chars[index] = replaceChar;
        }
        return new String(chars);
    }
}
