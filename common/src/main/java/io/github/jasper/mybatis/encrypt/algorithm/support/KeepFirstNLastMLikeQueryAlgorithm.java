package io.github.jasper.mybatis.encrypt.algorithm.support;

/**
 * 保留前 N 位和后 M 位，中间按替换字符覆盖。
 *
 * <p>语义参考 Apache ShardingSphere `KEEP_FIRST_N_LAST_M`。</p>
 */
public final class KeepFirstNLastMLikeQueryAlgorithm extends AbstractMaskLikeQueryAlgorithm {

    private final int firstN;
    private final int lastM;
    private final char replaceChar;

    public KeepFirstNLastMLikeQueryAlgorithm(int firstN, int lastM) {
        this(firstN, lastM, DEFAULT_REPLACE_CHAR);
    }

    public KeepFirstNLastMLikeQueryAlgorithm(int firstN, int lastM, char replaceChar) {
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
        if (plainText.length() < firstN + lastM) {
            return plainText;
        }
        char[] chars = plainText.toCharArray();
        for (int index = firstN; index < plainText.length() - lastM; index++) {
            chars[index] = replaceChar;
        }
        return new String(chars);
    }
}
