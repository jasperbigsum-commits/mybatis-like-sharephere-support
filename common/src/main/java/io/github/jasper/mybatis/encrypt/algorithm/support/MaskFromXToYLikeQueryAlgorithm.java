package io.github.jasper.mybatis.encrypt.algorithm.support;

/**
 * 覆盖闭区间 [X, Y]，区间外保持原值。
 *
 * <p>语义参考 Apache ShardingSphere `MASK_FROM_X_TO_Y`，索引按 0-based 闭区间处理。</p>
 */
public final class MaskFromXToYLikeQueryAlgorithm extends AbstractMaskLikeQueryAlgorithm {

    private final int fromX;
    private final int toY;
    private final char replaceChar;

    public MaskFromXToYLikeQueryAlgorithm(int fromX, int toY) {
        this(fromX, toY, DEFAULT_REPLACE_CHAR);
    }

    public MaskFromXToYLikeQueryAlgorithm(int fromX, int toY, char replaceChar) {
        requireNonNegative("fromX", fromX);
        requireNonNegative("toY", toY);
        requireRange(fromX, toY);
        this.fromX = fromX;
        this.toY = toY;
        this.replaceChar = replaceChar;
    }

    @Override
    public String transform(String plainText) {
        if (null == plainText || plainText.isEmpty()) {
            return plainText;
        }
        if (plainText.length() <= fromX) {
            return plainText;
        }
        char[] chars = plainText.toCharArray();
        for (int index = fromX, upperBound = Math.min(toY, chars.length - 1); index <= upperBound; index++) {
            chars[index] = replaceChar;
        }
        return new String(chars);
    }
}
