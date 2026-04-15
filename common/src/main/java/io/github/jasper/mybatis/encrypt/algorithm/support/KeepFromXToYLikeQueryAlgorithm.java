package io.github.jasper.mybatis.encrypt.algorithm.support;

/**
 * 仅保留闭区间 [X, Y]，区间外按替换字符覆盖。
 *
 * <p>语义参考 Apache ShardingSphere `KEEP_FROM_X_TO_Y`，索引按 0-based 闭区间处理。</p>
 */
public final class KeepFromXToYLikeQueryAlgorithm extends AbstractMaskLikeQueryAlgorithm {

    private final int fromX;
    private final int toY;
    private final char replaceChar;

    public KeepFromXToYLikeQueryAlgorithm(int fromX, int toY) {
        this(fromX, toY, DEFAULT_REPLACE_CHAR);
    }

    public KeepFromXToYLikeQueryAlgorithm(int fromX, int toY, char replaceChar) {
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
        char[] chars = plainText.toCharArray();
        for (int index = 0; index < Math.min(fromX, plainText.length()); index++) {
            chars[index] = replaceChar;
        }
        for (int index = toY + 1; index < chars.length; index++) {
            chars[index] = replaceChar;
        }
        return new String(chars);
    }
}
